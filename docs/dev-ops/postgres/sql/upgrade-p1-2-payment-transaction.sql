-- ============================================================================
-- P1-2 升级脚本（PostgreSQL 侧）：新增支付流水表 payment_transaction 并写入样例数据
--
-- 库：ai-rag-knowledge（与 pgvector 共库，端口 15432）
--
-- 为什么选这张表（而不是随便建一张 demo 表）：
--   payment_transaction.order_no 与 MySQL 的 ai_order.order_no 一一对应，且**没有脱敏**——
--   它是本项目第一个"可以真正做跨源键关联"的数据（订单的 user_id 是脱敏的，没法跨源比对）。
--   样例数据刻意对齐仓库里那个真实案情：
--     NO20260327001（MySQL: FAILED / PAY_TIMEOUT）→ 这里是一条 WECHAT 支付 **TIMEOUT**（网关耗时 30012ms）
--   于是"下单失败"的根因第一次有了跨源证据：不是限流，是支付网关超时。
--
-- 幂等性：可重复执行（建表 IF NOT EXISTS + 种子走 ON CONFLICT DO NOTHING）。
--
-- 执行方式（本机没装 psql 客户端，两种方式任选）：
--   1) 任意 PG 客户端：
--        psql -h 127.0.0.1 -p 15432 -U postgres -d ai-rag-knowledge -f upgrade-p1-2-payment-transaction.sql
--   2) 用仓库自带的集成测试（走 JDBC，自动读取本文件并执行）：
--        mvn -o test -Dtest=P1_2PaymentTransactionSeedTest
-- ============================================================================

CREATE TABLE IF NOT EXISTS payment_transaction (
    id          bigserial      PRIMARY KEY,
    order_no    varchar(64)    NOT NULL,
    pay_channel varchar(32)    NOT NULL,
    status      varchar(32)    NOT NULL,
    amount      numeric(10, 2),
    duration_ms integer,
    fail_code   varchar(64),
    create_time timestamp      NOT NULL,
    update_time timestamp      NOT NULL DEFAULT now(),
    CONSTRAINT uk_payment_order_no UNIQUE (order_no)
);

COMMENT ON TABLE payment_transaction IS '支付流水（P1-2 第三个数据源样例：与 ai_order.order_no 对应）';
COMMENT ON COLUMN payment_transaction.order_no IS '订单号（跨源关联键，与 MySQL ai_order.order_no 一致）';
COMMENT ON COLUMN payment_transaction.pay_channel IS '支付渠道：WECHAT / ALIPAY / UNIONPAY';
COMMENT ON COLUMN payment_transaction.status IS '支付状态：SUCCESS / TIMEOUT / FAILED';
COMMENT ON COLUMN payment_transaction.duration_ms IS '支付网关耗时（毫秒）——超时类故障的关键证据';
COMMENT ON COLUMN payment_transaction.fail_code IS '失败码：GW_TIMEOUT / BALANCE_NOT_ENOUGH ...';

INSERT INTO payment_transaction (order_no, pay_channel, status, amount, duration_ms, fail_code, create_time)
VALUES
    ('NO20260327001', 'WECHAT', 'TIMEOUT', 199.00, 30012, 'GW_TIMEOUT',           '2026-03-27 10:30:12'),
    ('NO20260327002', 'ALIPAY', 'SUCCESS', 299.00,   820, NULL,                   '2026-03-27 10:30:20'),
    ('NO20260327003', 'WECHAT', 'FAILED',  399.00,  1450, 'BALANCE_NOT_ENOUGH',   '2026-03-27 13:00:05'),
    ('NO20260327004', 'ALIPAY', 'SUCCESS',  59.90,   640, NULL,                   '2026-03-27 19:00:03')
ON CONFLICT (order_no) DO NOTHING;

-- 自检：应输出 4 行，其中 NO20260327001 为 TIMEOUT / GW_TIMEOUT
SELECT order_no, pay_channel, status, amount, duration_ms, fail_code, create_time
FROM payment_transaction
ORDER BY create_time;
