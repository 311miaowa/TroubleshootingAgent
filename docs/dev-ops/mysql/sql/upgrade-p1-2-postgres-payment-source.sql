-- ============================================================================
-- P1-2 升级脚本（MySQL 侧）：把「支付流水」注册为第三个数据源
--
-- 配套：先在 PostgreSQL 侧执行 docs/dev-ops/postgres/sql/upgrade-p1-2-payment-transaction.sql
--       （建 payment_transaction 表 + 样例数据），再来执行本脚本登记配置。
--
-- 登记内容：
--   ai_data_source         ds_pg_payment（ds_type=postgres，密码走环境变量 PGVECTOR_PASSWORD）
--   ai_data_source_entity  payments（capability=entity_query，模板里带 ${filters} → 启用通用过滤）
--   ai_data_source_field   7 个字段（其中 pay_channel / status / amount / fail_code 可过滤）
--
-- 两点设计说明：
--   1. **不需要改 ai_stage_tool_policy**：能力仍是 entity_query，执行阶段早已允许；
--      工具 query_payments 会被 DataSourceToolCallbackFactory 自动挂出（"接源不改 Agent 侧代码"）。
--   2. **密码不落库**：conn_config 只存环境变量名 passwordEnv=PGVECTOR_PASSWORD
--      （与 application-dev.yml 里 pgvector 数据源用的是同一个变量名，演示值 postgres）。
--      未注入时 M4 的健康检查会给出可操作 hint。
--
-- 幂等性：全部按唯一键判重，可重复执行。
--
-- 执行方式：
--   mysql -h127.0.0.1 -uroot -p ai-agent-station-study < upgrade-p1-2-postgres-payment-source.sql
-- ============================================================================

-- 1. 数据源实例
INSERT INTO `ai_data_source`
  (`ds_id`, `ds_name`, `ds_type`, `tenant_id`, `mcp_id`, `conn_config`, `time_field`, `max_rows`, `timeout_ms`, `status`)
SELECT 'ds_pg_payment', '支付流水(PostgreSQL)', 'postgres', 'default', NULL,
       '{"jdbcUrl":"jdbc:postgresql://127.0.0.1:15432/ai-rag-knowledge","username":"postgres","passwordEnv":"PGVECTOR_PASSWORD","driverClassName":"org.postgresql.Driver"}',
       'create_time', 200, 5000, 1
WHERE NOT EXISTS (SELECT 1 FROM `ai_data_source` WHERE ds_id = 'ds_pg_payment');

-- 2. 实体（query_template 里带 ${filters}：只有带这个占位符的模板才允许通用过滤）
INSERT INTO `ai_data_source_entity`
  (`ds_id`, `entity_code`, `entity_name`, `entity_desc`, `physical_name`, `capability`, `query_template`, `status`)
SELECT 'ds_pg_payment', 'payments', '支付流水',
       '支付网关流水。按订单号/时间窗查询支付渠道、支付状态、网关耗时与失败码，用于定位支付超时、渠道故障等原因。',
       'payment_transaction', 'entity_query',
       'SELECT order_no, pay_channel, status, amount, duration_ms, fail_code, create_time FROM payment_transaction WHERE create_time >= ${start} AND create_time <= ${end} ${filters} ORDER BY create_time DESC LIMIT ${limit}',
       1
WHERE NOT EXISTS (SELECT 1 FROM `ai_data_source_entity`
                  WHERE ds_id = 'ds_pg_payment' AND entity_code = 'payments');

-- 3. 字段字典（is_filterable=1 的字段才允许出现在 ${filters} 里）
--    注意：`sensitive` 是 MySQL 8.0 的保留字，必须加反引号（否则报 1064）
INSERT INTO `ai_data_source_field`
  (`ds_id`, `entity_code`, `field_name`, `field_label`, `field_type`, `is_time_field`, `is_filterable`, `sample_value`, `sensitive`, `status`)
SELECT * FROM (
  SELECT 'ds_pg_payment' AS ds_id, 'payments' AS entity_code, 'order_no'    AS field_name, '订单号'    AS field_label, 'varchar'  AS field_type, 0 AS is_time_field, 1 AS is_filterable, 'NO20260327001' AS sample_value, 0 AS `sensitive`, 1 AS `status`
  UNION ALL SELECT 'ds_pg_payment', 'payments', 'pay_channel', '支付渠道', 'varchar',  0, 1, 'WECHAT', 0, 1
  UNION ALL SELECT 'ds_pg_payment', 'payments', 'status',      '支付状态', 'varchar',  0, 1, 'TIMEOUT', 0, 1
  UNION ALL SELECT 'ds_pg_payment', 'payments', 'amount',      '支付金额', 'decimal',  0, 1, '199.00', 0, 1
  UNION ALL SELECT 'ds_pg_payment', 'payments', 'duration_ms', '网关耗时', 'integer',  0, 0, '30012', 0, 1
  UNION ALL SELECT 'ds_pg_payment', 'payments', 'fail_code',   '失败码',   'varchar',  0, 1, 'GW_TIMEOUT', 0, 1
  UNION ALL SELECT 'ds_pg_payment', 'payments', 'create_time', '支付时间', 'timestamp', 1, 1, '2026-03-27 10:30:12', 0, 1
) AS seed
WHERE NOT EXISTS (
  SELECT 1 FROM `ai_data_source_field` f
  WHERE f.ds_id = seed.ds_id AND f.entity_code = seed.entity_code AND f.field_name = seed.field_name
);

-- 4. 自检：应输出 3 个数据源、3 个实体、17 个字段（10 原有 + 7 新增）
SELECT 'ai_data_source' AS tbl, COUNT(*) AS rows_cnt FROM `ai_data_source`
UNION ALL SELECT 'ai_data_source_entity', COUNT(*) FROM `ai_data_source_entity`
UNION ALL SELECT 'ai_data_source_field', COUNT(*) FROM `ai_data_source_field`;

-- 回滚（如确需回滚，按顺序执行）：
-- DELETE FROM `ai_data_source_field`  WHERE ds_id = 'ds_pg_payment';
-- DELETE FROM `ai_data_source_entity` WHERE ds_id = 'ds_pg_payment';
-- DELETE FROM `ai_data_source`        WHERE ds_id = 'ds_pg_payment';
