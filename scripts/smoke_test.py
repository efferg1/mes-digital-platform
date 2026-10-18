# -*- coding: utf-8 -*-
"""
端到端冒烟测试：把核心业务链路真跑一遍。

覆盖：
  1. 创建工单 -> 下达 -> 报工（扣库存）-> 完整报满（状态转 FINISHED）
  2. 库存不足时整单回滚，库存不会被扣掉一半
  3. 超计划量报工被拒绝
  4. ERP 推送建单 + 重复推送幂等
  5. 知识库问答（RAG 检索）

用法：python scripts/smoke_test.py [base_url]
默认 http://localhost:18080
"""
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18080"

passed = 0
failed = 0


def call(method, path, body=None):
    """返回 (http_status, dict)"""
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json;charset=UTF-8"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "ignore")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}   {detail}")


def stock_of(materials, code):
    for m in materials:
        if m["code"] == code:
            return float(m["stockQty"])
    return -1.0


print("=" * 62)
print(" 制造业数字化协同平台 · 冒烟测试")
print(" 目标:", BASE)
print("=" * 62)

# ---------- 0. 基础数据 ----------
print("\n[0] 基础数据")
code, r = call("GET", "/api/materials")
check("物料列表可读", code == 200 and r["code"] == 0)
materials = r["data"]
before_gl = stock_of(materials, "M-GL-002")

code, r = call("GET", "/api/bom/P-PIPE-200")
check("BOM 可读（P-PIPE-200 有 5 种料）", code == 200 and len(r["data"]) == 5, r)

# ---------- 1. 创建 -> 下达 -> 报工 ----------
print("\n[1] 工单全流程")
code, r = call("POST", "/api/work-orders", {
    "productCode": "P-PIPE-200",
    "planQty": 50,
    "priority": 7,
    "dueDate": "2026-11-05",
    "remark": "冒烟测试单",
})
check("创建工单", code == 200 and r["code"] == 0, r)
wo = r["data"]
wo_id, wo_no = wo["id"], wo["orderNo"]
check("初始状态为 CREATED", wo["status"] == "CREATED", wo["status"])

code, r = call("POST", f"/api/work-orders/{wo_id}/release")
check("下达成功，状态转 RELEASED", code == 200 and r["data"]["status"] == "RELEASED", r)

code, r = call("POST", f"/api/work-orders/{wo_id}/report", {"qty": 20, "operator": "测试员"})
check("报工 20 件", code == 200 and r["code"] == 0, r)
rep = r["data"] if code == 200 else {}
check("累计完成 20/50", float(rep.get("doneQty", -1)) == 20.0, rep)
check("状态转 IN_PROGRESS", rep.get("status") == "IN_PROGRESS", rep.get("status"))
check("自动生成 5 条耗料记录", len(rep.get("consumed", [])) == 5, rep.get("consumed"))

# 核对库存：M-GL-002 单耗 1.6，报 20 件应扣 32
code, r = call("GET", "/api/materials")
after_gl = stock_of(r["data"], "M-GL-002")
check(f"库存联动扣减（{before_gl} -> {after_gl}，应减 32）", abs((before_gl - after_gl) - 32.0) < 0.001,
      f"实际减 {before_gl - after_gl}")

# 按工单号反查流水
code, r = call("GET", f"/api/inventory/txn?refNo={wo_no}")
check("按工单号能反查到耗料流水", code == 200 and len(r["data"]) == 5, r.get("data"))

# ---------- 2. 超量报工被拒 ----------
print("\n[2] 业务校验")
code, r = call("POST", f"/api/work-orders/{wo_id}/report", {"qty": 100, "operator": "测试员"})
check("超计划量报工被拒绝", code == 400, r)

code, r = call("POST", "/api/work-orders", {"productCode": "P-NOT-EXIST", "planQty": 10})
check("未维护 BOM 的产品不能投产", code == 400, r)

# ---------- 3. 报满转完工 ----------
print("\n[3] 报满转完工")
code, r = call("POST", f"/api/work-orders/{wo_id}/report", {"qty": 30, "operator": "测试员"})
check("补报 30 件", code == 200, r)
check("状态转 FINISHED", r["data"]["status"] == "FINISHED", r["data"].get("status"))

code, r = call("POST", f"/api/work-orders/{wo_id}/report", {"qty": 1, "operator": "测试员"})
check("已完工的工单不能再报工", code == 400, r)

# ---------- 4. 库存不足整单回滚 ----------
print("\n[4] 库存不足回滚")
code, r = call("GET", "/api/materials")
stock_map_before = {m["code"]: float(m["stockQty"]) for m in r["data"]}

code, r = call("POST", "/api/work-orders", {
    "productCode": "P-PIPE-200", "planQty": 999, "priority": 5,
    "dueDate": "2026-11-30", "remark": "故意让库存不足",
})
big_id = r["data"]["id"]
call("POST", f"/api/work-orders/{big_id}/release")
code, r = call("POST", f"/api/work-orders/{big_id}/report", {"qty": 999, "operator": "测试员"})
check("库存不足返回 400", code == 400, r)
check("错误信息里说明了哪种料不够", code == 400 and ("库存不足" in str(r.get("message", ""))),
      r.get("message"))

code, r = call("GET", "/api/materials")
stock_map_after = {m["code"]: float(m["stockQty"]) for m in r["data"]}
dirty = {k: (stock_map_before[k], stock_map_after[k])
         for k in stock_map_before if abs(stock_map_before[k] - stock_map_after[k]) > 0.001}
check("事务回滚：所有物料库存原封不动", not dirty, dirty)

# ---------- 5. 集成：ERP 建单 + 幂等 ----------
print("\n[5] 系统集成（ERP）")
erp_body = {
    "salesOrderNo": "SO-TEST-9001",
    "materialCode": "P-BOX-100",
    "materialName": "白色塑料周转箱",
    "orderQty": 300,
    "deliveryDate": "2026-11-20",
    "customerCode": "C-001",
    "remark": "冒烟测试推送",
}
code, r = call("POST", "/api/integration/erp/sales-orders", erp_body)
check("ERP 订单 -> 自动建工单", code == 200 and r["data"]["result"] == "SUCCESS", r)
first_order_no = r["data"].get("orderNo")

code, r = call("POST", "/api/integration/erp/sales-orders", erp_body)
check("重复推送被识别为 DUPLICATE", code == 200 and r["data"]["result"] == "DUPLICATE", r)

code, r = call("POST", "/api/integration/erp/sales-orders", {
    "salesOrderNo": "SO-TEST-9002",
    "materialCode": "P-NOT-EXIST",
    "orderQty": 10,
})
check("物料不存在时集成失败并记录日志", code == 400, r)

code, r = call("GET", "/api/integration/logs?limit=10")
logs = r["data"]
# 重复推送不会新增日志（幂等直接返回 DUPLICATE），所以这里只有成功 + 失败两条
check("集成日志可查", len(logs) >= 2, len(logs))
statuses = [(x["bizId"], x["status"]) for x in logs]
check("日志里能看到 DUPLICATE 之外的成功与失败记录",
      any(s == "SUCCESS" for _, s in statuses) and any(s == "FAILED" for _, s in statuses), statuses)

# ---------- 6. AI 知识库问答 ----------
print("\n[6] AI 知识库问答")
code, r = call("GET", "/api/ai/status")
check("知识库状态可读", code == 200 and r["data"]["chunks"] > 0, r.get("data"))
print("     切片数:", r["data"]["chunks"], "| 检索方式:", r["data"]["retriever"],
      "| 大模型已配置:", r["data"]["llm"]["configured"])

for q in ["注塑机液压油温超过 60 度会怎样", "报工之后库存是怎么扣的", "ERP 重复推送同一个订单会重复建单吗"]:
    code, r = call("POST", "/api/ai/ask", {"question": q, "topK": 3})
    d = r.get("data") or {}
    ok = code == 200 and d.get("mode") in ("LLM", "RETRIEVAL", "RETRIEVAL_FALLBACK") and len(d.get("sources", [])) > 0
    print(f"  问：{q}")
    print(f"    模式={d.get('mode')} 命中={len(d.get('sources', []))} 耗时={d.get('elapsedMs')}ms")
    top = (d.get("sources") or [{}])[0]
    print(f"    首条出处：《{top.get('docTitle')}》第{top.get('seq')}段 score={top.get('score')}")
    check(f"「{q[:12]}…」能检索到资料", ok, d)

# ---------- 汇总 ----------
print("\n" + "=" * 62)
print(f" 结果：PASS {passed} / FAIL {failed}")
print("=" * 62)
sys.exit(0 if failed == 0 else 1)
