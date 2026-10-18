package com.cuijian.digital;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 制造业数字化协同平台
 *
 * 覆盖四块能力：
 *   1. 生产工单全流程（创建 / 下达 / 报工 / 完工），报工时按 BOM 联动扣减库存
 *   2. 异构系统集成（ERP 销售订单、TMS 发运单、CRM 客户），适配器 + 幂等 + 日志
 *   3. 数据库设计与 SQL：8 张表、联合索引、分页与聚合查询
 *   4. AI 知识库问答（RAG）：文档切片 -> 检索 -> 拼 Prompt -> 调大模型，无 Key 时自动降级
 */
@SpringBootApplication
public class DigitalPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigitalPlatformApplication.class, args);
    }
}
