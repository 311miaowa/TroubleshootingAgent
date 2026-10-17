-- ============================================================================
-- M8 升级脚本：新增「故障期/基线对比」能力 fault_window 的阶段策略
--
-- 背景：
--   M8 新增了语义工具 query_fault_baseline（把故障期与对称正常基线放在一起对比），
--   能力编码为 fault_window。工具是否挂给某个阶段，由 ai_stage_tool_policy 决定
--   （见 DataSourceToolCallbackFactory.buildFor）：
--     - PRECISION_EXECUTOR_CLIENT（执行阶段）→ allowed = 1（只有执行阶段取数对比）
--     - TASK_ANALYZER_CLIENT / QUALITY_SUPERVISOR_CLIENT / RESPONSE_ASSISTANT → allowed = 0
--       （分析/监督阶段保持"只看实体字典"，避免四步链被短路——纪律来自 M1.2）
--
--   ⚠️ 跳过本脚本的后果：fault_window 不在策略表里 → 白名单里没有它 → **工具不会挂载**
--   （与 P1 cross_source、M7a anomaly_summary 同一个坑）。
--
--   ⚠️ 另：故障窗由请求侧（faultStart/faultEnd/prevFaultEnd/nextFaultStart）提供并注入 ToolContext；
--   不传故障窗时工具仍会挂载，但调用会返回"未提供故障窗"的告警——这是有意的（不猜窗口）。
--
-- 依赖：需先执行 upgrade-m1-0-datasource-tables.sql（建 ai_stage_tool_policy）。
-- 幂等：按 (stage_code, capability) 判重，可重复执行；已存在的行不改动。
--
-- 执行方式：
--   mysql -h127.0.0.1 -uroot -p ai-agent-station-study < upgrade-m8-1-fault-window.sql
-- ============================================================================

SET @exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_stage_tool_policy');

SET @sql := IF(@exists > 0,
  'INSERT INTO `ai_stage_tool_policy` (stage_code, capability, allowed)
   SELECT * FROM (
     SELECT ''TASK_ANALYZER_CLIENT'' AS stage_code, ''fault_window'' AS capability, 0 AS allowed
     UNION ALL SELECT ''PRECISION_EXECUTOR_CLIENT'', ''fault_window'', 1
     UNION ALL SELECT ''QUALITY_SUPERVISOR_CLIENT'', ''fault_window'', 0
     UNION ALL SELECT ''RESPONSE_ASSISTANT'', ''fault_window'', 0
   ) AS seed
   WHERE NOT EXISTS (
     SELECT 1 FROM `ai_stage_tool_policy` p
     WHERE p.stage_code = seed.stage_code AND p.capability = seed.capability
   )',
  'SELECT ''ai_stage_tool_policy missing - run upgrade-m1-0-datasource-tables.sql first'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 自检：应输出 4 行 fault_window 策略，其中 PRECISION_EXECUTOR_CLIENT = 1
SELECT stage_code, capability, allowed
FROM `ai_stage_tool_policy`
WHERE capability = 'fault_window'
ORDER BY allowed DESC, stage_code;

-- 回滚（如需撤销本能力，手工执行）：
-- DELETE FROM `ai_stage_tool_policy` WHERE capability = 'fault_window';
