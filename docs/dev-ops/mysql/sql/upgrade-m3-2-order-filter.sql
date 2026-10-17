-- ============================================================================
-- M3.2 升级脚本：订单号精确过滤接线（order_no 结构化过滤）
--
-- 背景（见 docs/dev-ops/M3-evidence-panel-design.md §3 / §6 M3.2）：
--   M1.3 的 query_template 只接线了时间窗 + limit，order_no 过滤未接线。
--   本脚本给 ds_order_mysql 的 query_template 加 ${orderNo} 占位符，
--   由 MysqlOrderProvider 渲染成 ? 并经 PreparedStatement 绑定（禁止模型拼 SQL）。
--
-- 幂等：UPDATE 为定值，可重复执行。
--
-- 执行方式：
--   $env:MYSQL_PWD='<你的 MySQL 口令>'
--   Get-Content -Raw -Encoding UTF8 upgrade-m3-2-order-filter.sql |
--     & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' `
--       --host=127.0.0.1 --port=3306 --user=root --default-character-set=utf8mb4 ai-agent-station-study
-- ============================================================================

UPDATE `ai_data_source_entity`
SET `query_template` = 'SELECT order_no, user_id, status, amount, fail_reason, create_time FROM ai_order WHERE create_time >= ${start} AND create_time <= ${end} AND (order_no = ${orderNo} OR ${orderNo} IS NULL) ORDER BY create_time DESC LIMIT ${limit}'
WHERE `ds_id` = 'ds_order_mysql' AND `entity_code` = 'orders';

-- 校验：应看到 query_template 含 ${orderNo}
SELECT `ds_id`, `entity_code`, `capability`, `query_template`
FROM `ai_data_source_entity` WHERE `ds_id` = 'ds_order_mysql' AND `entity_code` = 'orders';

-- 回滚（恢复 M1.3 的无过滤版本）：
-- UPDATE `ai_data_source_entity`
-- SET `query_template` = 'SELECT order_no, user_id, status, amount, fail_reason, create_time FROM ai_order WHERE create_time >= ${start} AND create_time <= ${end} ORDER BY create_time DESC LIMIT ${limit}'
-- WHERE `ds_id` = 'ds_order_mysql' AND `entity_code` = 'orders';
