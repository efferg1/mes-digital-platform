-- =============================================================
--  制造业数字化协同平台 · 表结构
--  默认 H2（MySQL 兼容模式）；切 MySQL 时本脚本可直接执行
-- =============================================================

-- 物料主数据
CREATE TABLE IF NOT EXISTS material (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    spec          VARCHAR(64),
    unit          VARCHAR(8)   NOT NULL DEFAULT 'PCS',
    stock_qty     DECIMAL(14,2) NOT NULL DEFAULT 0,
    safety_stock  DECIMAL(14,2) NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_material_code ON material(code);


-- 产品 BOM：一个成品需要哪些物料、各多少
CREATE TABLE IF NOT EXISTS bom_item (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_code  VARCHAR(32)   NOT NULL,
    material_code VARCHAR(32)   NOT NULL,
    qty_per       DECIMAL(14,4) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_bom ON bom_item(product_code, material_code);


-- 生产工单：从 ERP 销售订单或手工创建
CREATE TABLE IF NOT EXISTS work_order (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no         VARCHAR(32)  NOT NULL,
    product_code     VARCHAR(32)  NOT NULL,
    product_name     VARCHAR(64),
    plan_qty         DECIMAL(14,2) NOT NULL,
    done_qty         DECIMAL(14,2) NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL,          -- CREATED / RELEASED / IN_PROGRESS / FINISHED / CANCELLED
    priority         INT          NOT NULL DEFAULT 5,
    due_date         DATE,
    source_system    VARCHAR(16),                    -- ERP / MANUAL
    source_order_no  VARCHAR(32),
    remark           VARCHAR(255),
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wo_no ON work_order(order_no);
-- 列表页默认按状态过滤 + 按创建时间倒序，联合索引避免全表扫
CREATE INDEX IF NOT EXISTS idx_wo_status_created ON work_order(status, created_at);
CREATE INDEX IF NOT EXISTS idx_wo_source ON work_order(source_system, source_order_no);


-- 工单工序（简化：每张工单固定 3 道工序）
CREATE TABLE IF NOT EXISTS work_order_step (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_order_id BIGINT       NOT NULL,
    seq           INT          NOT NULL,
    name          VARCHAR(32)  NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',   -- PENDING / RUNNING / DONE
    operator      VARCHAR(32),
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_step_wo ON work_order_step(work_order_id, seq);


-- 库存流水：每一次扣减都留痕，可追溯
CREATE TABLE IF NOT EXISTS inventory_txn (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_code VARCHAR(32)   NOT NULL,
    delta         DECIMAL(14,2) NOT NULL,      -- 负数=出库，正数=入库
    txn_type      VARCHAR(16)   NOT NULL,      -- ISSUE / RECEIPT / ADJUST
    ref_no        VARCHAR(32),                 -- 关联工单号
    balance_after DECIMAL(14,2) NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_txn_material ON inventory_txn(material_code, created_at DESC);


-- 集成日志：外部系统每次进来一条，失败可查、可重放
CREATE TABLE IF NOT EXISTS integration_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    system        VARCHAR(16)  NOT NULL,       -- ERP / TMS / CRM
    biz_type      VARCHAR(32)  NOT NULL,       -- SALES_ORDER / SHIPMENT / CUSTOMER
    biz_id        VARCHAR(64)  NOT NULL,
    direction     VARCHAR(8)   NOT NULL,       -- IN / OUT
    status        VARCHAR(16)  NOT NULL,       -- SUCCESS / FAILED / DUPLICATE
    payload       TEXT,
    message       VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 幂等判断：同一系统 + 同一业务号只处理一次
CREATE UNIQUE INDEX IF NOT EXISTS uk_integration ON integration_log(system, biz_type, biz_id);
CREATE INDEX IF NOT EXISTS idx_integ_status ON integration_log(status, created_at DESC);


-- 知识库文档（设备手册、工艺文件、SOP）
CREATE TABLE IF NOT EXISTS kb_document (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    title         VARCHAR(128) NOT NULL,
    category      VARCHAR(32),
    source        VARCHAR(128),
    content       TEXT         NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_title ON kb_document(title);


-- 文档切片：RAG 检索的最小单位
CREATE TABLE IF NOT EXISTS kb_chunk (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id        BIGINT       NOT NULL,
    seq           INT          NOT NULL,
    content       VARCHAR(1000) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chunk_doc ON kb_chunk(doc_id, seq);
