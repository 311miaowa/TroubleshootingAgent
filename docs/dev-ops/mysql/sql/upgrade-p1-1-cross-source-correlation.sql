-- ============================================================================
-- P1-1 升级脚本：新增「跨源时间线对齐」能力 cross_source 的阶段策略
--
-- 背景：
--   跨源关联（P1）新增了语义工具 query_correlated_timeline，能力编码为 cross_source。
--   工具是否挂给某个阶段，由 ai_stage_tool_policy 决定（见 DataSourceToolCallbackFactory）：
--     - PRECISION_EXECUTOR_CLIENT（执行阶段）→ allowed = 1（只有它需要、也才允许做跨源检索）
--     - TASK_ANALYZER_CLIENT / QUALITY_SUPERVISOR_CLIENT / RESPONSE_ASSISTANT → allowed = 0
--       （分析/监督阶段保持"只看实体字典"，避免四步链被短路——这条纪律来自 M1.2）
--
-- 幂等性：
--   按 (stage_code, capability) 判重，可重复执行；已存在的行不改动（allowed 不会被覆盖，
--   方便你在库里手工调整后不被脚本回滚）。
--
-- 执行方式：
--   mysql -h127.0.0.1 -uroot -p ai-agent-station-study < upgrade-p1-1-cross-source-correlation.sql
-- ============================================================================

SET @exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_stage_tool_policy');

SET @sql := IF(@exists > 0,
  'INSERT INTO `ai_stage_tool_policy` (stage_code, capability, allowed)
   SELECT * FROM (
     SELECT ''TASK_ANALYZER_CLIENT'' AS stage_code, ''cross_source'' AS capability, 0 AS allowed
     UNION ALL SELECT ''PRECISION_EXECUTOR_CLIENT'', ''cross_source'', 1
     UNION ALL SELECT ''QUALITY_SUPERVISOR_CLIENT'', ''cross_source'', 0
     UNION ALL SELECT ''RESPONSE_ASSISTANT'', ''cross_source'', 0
   ) AS seed
   WHERE NOT EXISTS (
     SELECT 1 FROM `ai_stage_tool_policy` p
     WHERE p.stage_code = seed.stage_code AND p.capability = seed.capability
   )',
  'SELECT ''ai_stage_tool_policy 不存在，跳过（请先执行 upgrade-m1-0-datasource-tables.sql）'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 自检：应输出 4 行 cross_source 策略，其中 PRECISION_EXECUTOR_CLIENT = 1
SELECT stage_code, capability, allowed
FROM `ai_stage_tool_policy`
WHERE capability = 'cross_source'
ORDER BY allowed DESC, stage_code;
