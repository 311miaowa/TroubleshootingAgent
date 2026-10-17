-- ============================================================================
-- M0-1 升级脚本：为 ai_agent_flow_config 增加 update_time 列
--
-- 背景：
--   配置热更新依赖「全表 MAX(update_time) 变更检测」。但 step_prompt（各阶段提示词）
--   存放在 ai_agent_flow_config 中，而该表此前只有 create_time，
--   导致"改数据库中的 Prompt 即生效"在数据模型层面无法实现。
--
-- 幂等性：
--   本脚本在执行前会先判断列是否已存在，可重复执行。
--
-- 执行方式：
--   mysql -h127.0.0.1 -uroot -p ai-agent-station-study < upgrade-m0-1-flow-config-update-time.sql
-- ============================================================================

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_agent_flow_config'
      AND COLUMN_NAME = 'update_time'
);

SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `ai_agent_flow_config`
        ADD COLUMN `update_time` datetime DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间'' AFTER `create_time`',
    'SELECT ''ai_agent_flow_config.update_time 已存在，跳过'' AS msg');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
