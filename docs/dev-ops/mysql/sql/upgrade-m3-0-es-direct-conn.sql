-- ============================================================================
-- M3.0 升级脚本：ES 日志源改为直连 _search（拿 _id/_index 逐条回指原文）
--
-- 背景（见 docs/dev-ops/M3-evidence-panel-design.md §2 决策 1）：
--   原 ES 走 MCP 的 search，返回平铺文本且不含 _id，证据面板无法逐条回指原文。
--   M3.0 起 ElasticsearchProvider 直连 ES 的 _search HTTP API，需给 ds_log_es
--   补 conn_config（host 必填，auth 可选）。
--
-- 本地 ES 默认未启用安全特性（无认证），auth 省略。
-- 生产环境若启用安全，按需补 auth（如 "ApiKey xxx" 或 "Basic base64(user:pass)"）：
--   {"host":"https://es.example.com:9200","auth":"ApiKey <your-key>"}
--
-- 幂等：UPDATE 为定值，可重复执行。
--
-- 执行方式：
--   $env:MYSQL_PWD='<你的 MySQL 口令>'
--   Get-Content -Raw -Encoding UTF8 upgrade-m3-0-es-direct-conn.sql |
--     & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' `
--       --host=127.0.0.1 --port=3306 --user=root --default-character-set=utf8mb4 ai-agent-station-study
-- ============================================================================

UPDATE `ai_data_source`
SET `conn_config` = '{"host":"http://127.0.0.1:9200"}'
WHERE `ds_id` = 'ds_log_es';

-- 校验：应看到 ds_log_es 的 conn_config 已非 NULL，且 ds_order_mysql 不变
SELECT `ds_id`, `ds_type`, `mcp_id`, `conn_config`, `status`
FROM `ai_data_source`
WHERE `ds_id` IN ('ds_log_es', 'ds_order_mysql');

-- 回滚（恢复为经 MCP 接入，conn_config 置空）：
-- UPDATE `ai_data_source` SET `conn_config` = NULL WHERE `ds_id` = 'ds_log_es';
