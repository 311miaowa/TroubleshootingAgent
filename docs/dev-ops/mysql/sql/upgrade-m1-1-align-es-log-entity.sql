-- ============================================================================
-- M1.1 对齐脚本：把 ds_log_es 的实体/字段字典对齐到本机真实 ES 索引
--
-- 背景：
--   M1.0 的种子数据里 physical_name 是示意值 ai-agent-station-study-log-*，
--   而本机 Elasticsearch 的真实日志索引是 group-buy-market-log-2026.03.27
--   （字段：@timestamp / level / message / application）。
--   M1.1 的验收是"排查请求仍能命中 ES 真实数据"，必须把配置对齐到真实索引。
--   同时把 entity_code 从 log 改为 logs，使语义工具名为 query_logs（与设计一致）。
--
-- 幂等：实体 UPDATE 为定值；字段字典先 DELETE 再 INSERT，可重复执行。
--
-- 执行方式：
--   $env:MYSQL_PWD='<你的 MySQL 口令>'
--   Get-Content -Raw -Encoding UTF8 upgrade-m1-1-align-es-log-entity.sql |
--     & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' `
--       --host=127.0.0.1 --port=3306 --user=root --default-character-set=utf8mb4 ai-agent-station-study
-- ============================================================================

-- 1. 实体：entity_code=logs + 物理索引模式 + 查询模板
UPDATE `ai_data_source_entity`
SET `entity_code` = 'logs',
    `physical_name` = 'group-buy-market-log-*',
    `query_template` = '{"query":{"bool":{"must":[{"range":{"@timestamp":{"gte":"${start}","lte":"${end}"}}},{"match":{"message":"${keyword}"}}]}},"sort":[{"@timestamp":{"order":"desc"}}],"size":${size}}'
WHERE `ds_id` = 'ds_log_es' AND `entity_code` IN ('log', 'logs');

-- 2. 字段字典：删旧重建（entity_code=logs，真实字段）
DELETE FROM `ai_data_source_field` WHERE `ds_id` = 'ds_log_es' AND `entity_code` IN ('log', 'logs');

INSERT INTO `ai_data_source_field`
  (`ds_id`, `entity_code`, `field_name`, `field_label`, `field_type`, `is_time_field`, `is_filterable`, `sample_value`, `sensitive`, `status`)
VALUES
  ('ds_log_es', 'logs', '@timestamp', '日志时间', 'date', 1, 1, '2026-03-27T15:00:00+08:00', 0, 1),
  ('ds_log_es', 'logs', 'level',      '日志级别', 'keyword', 0, 1, 'INFO', 0, 1),
  ('ds_log_es', 'logs', 'message',    '日志内容', 'text', 0, 0, '测试日志', 0, 1),
  ('ds_log_es', 'logs', 'application','应用名',   'keyword', 0, 1, 'test', 0, 1);

-- 3. 校验
SELECT `ds_id`, `entity_code`, `physical_name`, `capability`, `query_template`
FROM `ai_data_source_entity` WHERE `ds_id` = 'ds_log_es' AND `entity_code` = 'logs';

SELECT `ds_id`, `entity_code`, `field_name`, `field_label`, `field_type`, `is_time_field`, `is_filterable`, `sensitive`
FROM `ai_data_source_field` WHERE `ds_id` = 'ds_log_es' AND `entity_code` = 'logs' ORDER BY `id`;
