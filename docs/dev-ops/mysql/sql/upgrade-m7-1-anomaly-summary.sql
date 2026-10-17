-- ============================================================================
-- M7a 升级脚本：新增「异常归纳」能力 anomaly_summary 的阶段策略
--
-- 背景：
--   M7a 新增了语义工具 query_log_summary（把原始日志归纳成"一行 = 一个现象"的统计摘要），
--   能力编码为 anomaly_summary。工具是否挂给某个阶段，由 ai_stage_tool_policy 决定
--   （见 DataSourceToolCallbackFactory.buildFor）：
--     - PRECISION_EXECUTOR_CLIENT（执行阶段）→ allowed = 1（只有执行阶段能取数归纳）
--     - TASK_ANALYZER_CLIENT / QUALITY_SUPERVISOR_CLIENT / RESPONSE_ASSISTANT → allowed = 0
--       （分析/监督阶段保持"只看实体字典"，避免四步链被短路——纪律来自 M1.2）
--
--   ⚠️ 跳过本脚本的后果：anomaly_summary 不在策略表里 → resolveAllowedCapabilities 返回的
--   白名单里没有它 → **工具不会挂载**（与 P1 cross_source 同一个坑）。
--
-- 依赖：需先执行 upgrade-m1-0-datasource-tables.sql（建 ai_stage_tool_policy）。
--
-- 幂等性：
--   按 (stage_code, capability) 判重，可重复执行；已存在的行不改动（allowed 不会被覆盖，
--   方便你在库里手工调整后不被脚本回滚）。
--
-- 执行方式：
--   mysql -h127.0.0.1 -uroot -p ai-agent-station-study < upgrade-m7-1-anomaly-summary.sql
-- ============================================================================

SET @exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_stage_tool_policy');

SET @sql := IF(@exists > 0,
  'INSERT INTO `ai_stage_tool_policy` (stage_code, capability, allowed)
   SELECT * FROM (
     SELECT ''TASK_ANALYZER_CLIENT'' AS stage_code, ''anomaly_summary'' AS capability, 0 AS allowed
     UNION ALL SELECT ''PRECISION_EXECUTOR_CLIENT'', ''anomaly_summary'', 1
     UNION ALL SELECT ''QUALITY_SUPERVISOR_CLIENT'', ''anomaly_summary'', 0
     UNION ALL SELECT ''RESPONSE_ASSISTANT'', ''anomaly_summary'', 0
   ) AS seed
   WHERE NOT EXISTS (
     SELECT 1 FROM `ai_stage_tool_policy` p
     WHERE p.stage_code = seed.stage_code AND p.capability = seed.capability
   )',
  'SELECT ''ai_stage_tool_policy 不存在，跳过（请先执行 upgrade-m1-0-datasource-tables.sql）'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 自检：应输出 4 行 anomaly_summary 策略，其中 PRECISION_EXECUTOR_CLIENT = 1
SELECT stage_code, capability, allowed
FROM `ai_stage_tool_policy`
WHERE capability = 'anomaly_summary'
ORDER BY allowed DESC, stage_code;

-- 回滚（如需撤销本能力，手工执行）：
-- DELETE FROM `ai_stage_tool_policy` WHERE capability = 'anomaly_summary';
