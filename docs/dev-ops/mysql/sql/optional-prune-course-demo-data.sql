-- ============================================================================
-- 可选脚本：清理「已有数据库」里的课程演示数据（二开清理，2026-10-10）
-- ============================================================================
--
-- 【用途】
--   建库基线 docs/dev-ops/mysql/sql/ai-agent-station-study.sql 已经裁剪掉课程演示数据。
--   但**已经存在的库**（例如本机 3306 上正在跑的那个）里还留着这些行。本脚本用于就地清理，
--   让老库与新基线保持一致。
--
-- 【什么时候**不需要**跑】
--   如果你打算直接按新基线重建库（DROP DATABASE 后重新执行基线 + upgrade-*.sql），就不用跑本脚本。
--
-- 【不做什么】
--   1) 不动业务表：ai_data_source / ai_data_source_entity / ai_data_source_field / ai_stage_tool_policy
--      以及 ai_agent(agent_id=4) / ai_client(4101-4104) / ai_client_api(1001) / ai_client_model(2003)
--      / ai_client_advisor(4001) / ai_client_system_prompt(7101-7104) / ai_client_tool_mcp(5007)
--      / ai_client_config(4101-4104 的绑定) —— 这些是当前项目的运行依赖。
--   2) 不删任何表结构，只有一个**默认注释掉**的可选 DROP（见 §5）。
--   3) 不碰 ai_client_rag_order 的**表**：配置热更新指纹 SQL（ai_client_config_mapper.xml 的
--      12 表 UNION）引用它，删表会让指纹查询报错、退化成"每 60s 全量重载"。只清它的演示行。
--
-- 【安全约定（照抄执行）】
--   1) 先备份：§1 为每张受影响的表建一份 `*_bak_20261010` 全量副本（幂等：先 DROP 再 CREATE）。
--   2) 再删除：§3 的 DELETE 全部在主键/唯一键上，重复执行无副作用（幂等）。
--   3) 有回滚：§6 用备份表把行放回去。
--   4) 与 UPGRADE-hot-reload-deepseek.md §二 的"不要硬 DELETE 配置行、停用请 status=0"约定不冲突：
--      那条约定针对**运行期热更新**（保留审计线）；本脚本是**一次性重建基线**，语义不同。
--      若你更希望"可随时开关"，把 §3 的 DELETE 换成 `UPDATE ... SET status = 0` 即可。
--
-- 【执行方式】
--   mysql -h 127.0.0.1 -u root -p -D "ai-agent-station-study" < optional-prune-course-demo-data.sql
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 备份（幂等：先删旧备份再重建）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `bak_20261010_ai_agent`;
CREATE TABLE `bak_20261010_ai_agent` AS SELECT * FROM `ai_agent`;

DROP TABLE IF EXISTS `bak_20261010_ai_agent_flow_config`;
CREATE TABLE `bak_20261010_ai_agent_flow_config` AS SELECT * FROM `ai_agent_flow_config`;

DROP TABLE IF EXISTS `bak_20261010_ai_agent_task_schedule`;
CREATE TABLE `bak_20261010_ai_agent_task_schedule` AS SELECT * FROM `ai_agent_task_schedule`;

DROP TABLE IF EXISTS `bak_20261010_ai_client`;
CREATE TABLE `bak_20261010_ai_client` AS SELECT * FROM `ai_client`;

DROP TABLE IF EXISTS `bak_20261010_ai_client_config`;
CREATE TABLE `bak_20261010_ai_client_config` AS SELECT * FROM `ai_client_config`;

DROP TABLE IF EXISTS `bak_20261010_ai_client_model`;
CREATE TABLE `bak_20261010_ai_client_model` AS SELECT * FROM `ai_client_model`;

DROP TABLE IF EXISTS `bak_20261010_ai_client_advisor`;
CREATE TABLE `bak_20261010_ai_client_advisor` AS SELECT * FROM `ai_client_advisor`;

DROP TABLE IF EXISTS `bak_20261010_ai_client_system_prompt`;
CREATE TABLE `bak_20261010_ai_client_system_prompt` AS SELECT * FROM `ai_client_system_prompt`;

DROP TABLE IF EXISTS `bak_20261010_ai_client_tool_mcp`;
CREATE TABLE `bak_20261010_ai_client_tool_mcp` AS SELECT * FROM `ai_client_tool_mcp`;

DROP TABLE IF EXISTS `bak_20261010_ai_client_rag_order`;
CREATE TABLE `bak_20261010_ai_client_rag_order` AS SELECT * FROM `ai_client_rag_order`;

-- 备份行数自检：应与清理前一致
SELECT 'backup' AS phase, 'ai_agent' AS tbl, COUNT(*) AS rows_ FROM `bak_20261010_ai_agent`
UNION ALL SELECT 'backup', 'ai_agent_flow_config', COUNT(*) FROM `bak_20261010_ai_agent_flow_config`
UNION ALL SELECT 'backup', 'ai_agent_task_schedule', COUNT(*) FROM `bak_20261010_ai_agent_task_schedule`
UNION ALL SELECT 'backup', 'ai_client', COUNT(*) FROM `bak_20261010_ai_client`
UNION ALL SELECT 'backup', 'ai_client_config', COUNT(*) FROM `bak_20261010_ai_client_config`
UNION ALL SELECT 'backup', 'ai_client_model', COUNT(*) FROM `bak_20261010_ai_client_model`
UNION ALL SELECT 'backup', 'ai_client_advisor', COUNT(*) FROM `bak_20261010_ai_client_advisor`
UNION ALL SELECT 'backup', 'ai_client_system_prompt', COUNT(*) FROM `bak_20261010_ai_client_system_prompt`
UNION ALL SELECT 'backup', 'ai_client_tool_mcp', COUNT(*) FROM `bak_20261010_ai_client_tool_mcp`
UNION ALL SELECT 'backup', 'ai_client_rag_order', COUNT(*) FROM `bak_20261010_ai_client_rag_order`;

-- ----------------------------------------------------------------------------
-- 2. 删除课程演示行（幂等）
--    删除范围（依据三份只读审计报告）：
--      agents 1/2/3、clients 3001-3005/3101-3104、MCP 5001-5006、RAG 顾问 4002、
--      models 2001/2002、prompts 6001-6004/6101-6104、RAG 知识库行 9001、定时任务行
--    保留范围：agent 4 / clients 4101-4104 / api 1001 / model 2003 / advisor 4001 /
--      prompts 7101-7104 / MCP 5007（含 model 2003→5007 绑定）
-- ----------------------------------------------------------------------------

-- 3.1 智能体与四步链
DELETE FROM `ai_agent` WHERE `agent_id` IN ('1', '2', '3');
DELETE FROM `ai_agent_flow_config` WHERE `agent_id` IN ('1', '2', '3');

-- 3.2 课程客户端（及其全部绑定）
DELETE FROM `ai_client_config` WHERE `source_id` IN
  ('3001', '3002', '3003', '3004', '3005', '3101', '3102', '3103', '3104');
DELETE FROM `ai_client` WHERE `client_id` IN
  ('3001', '3002', '3003', '3004', '3005', '3101', '3102', '3103', '3104');

-- 3.3 课程模型 / 顾问 / 提示词 / MCP（注意：绝不动 model 2003、advisor 4001、prompt 7101-7104、mcp 5007）
DELETE FROM `ai_client_config` WHERE `target_id` IN
  ('2001', '2002', '4002', '5001', '5002', '5003', '5004', '5005', '5006',
   '6001', '6002', '6003', '6004', '6101', '6102', '6103', '6104');
DELETE FROM `ai_client_model` WHERE `model_id` IN ('2001', '2002');
DELETE FROM `ai_client_advisor` WHERE `advisor_id` = '4002';
DELETE FROM `ai_client_system_prompt` WHERE `prompt_id` IN
  ('6001', '6002', '6003', '6004', '6101', '6102', '6103', '6104');
DELETE FROM `ai_client_tool_mcp` WHERE `mcp_id` IN
  ('5001', '5002', '5003', '5004', '5005', '5006');

-- 3.4 RAG 知识库演示行（表保留！见文件头【不做什么】第 3 条）
DELETE FROM `ai_client_rag_order` WHERE `rag_id` = '9001';

-- 3.5 定时任务（课程"自动发帖"）：代码侧 DAO/PO/mapper 已随二开清理删除
DELETE FROM `ai_agent_task_schedule`;

-- ----------------------------------------------------------------------------
-- 4. 验证：应看到 agent 只剩 4、client 只剩 4101-4104、mcp 只剩 5007
-- ----------------------------------------------------------------------------
SELECT 'after' AS phase, 'ai_agent' AS tbl, COUNT(*) AS rows_ FROM `ai_agent`
UNION ALL SELECT 'after', 'ai_agent_flow_config', COUNT(*) FROM `ai_agent_flow_config`
UNION ALL SELECT 'after', 'ai_agent_task_schedule', COUNT(*) FROM `ai_agent_task_schedule`
UNION ALL SELECT 'after', 'ai_client', COUNT(*) FROM `ai_client`
UNION ALL SELECT 'after', 'ai_client_config', COUNT(*) FROM `ai_client_config`
UNION ALL SELECT 'after', 'ai_client_model', COUNT(*) FROM `ai_client_model`
UNION ALL SELECT 'after', 'ai_client_advisor', COUNT(*) FROM `ai_client_advisor`
UNION ALL SELECT 'after', 'ai_client_system_prompt', COUNT(*) FROM `ai_client_system_prompt`
UNION ALL SELECT 'after', 'ai_client_tool_mcp', COUNT(*) FROM `ai_client_tool_mcp`
UNION ALL SELECT 'after', 'ai_client_rag_order', COUNT(*) FROM `ai_client_rag_order`;

SELECT `agent_id`, `agent_name` FROM `ai_agent`;
SELECT `client_id`, `client_name` FROM `ai_client` ORDER BY `client_id`;
SELECT `mcp_id`, `mcp_name`, `status` FROM `ai_client_tool_mcp`;

-- ----------------------------------------------------------------------------
-- 5.（可选，默认注释）彻底移除定时任务表
--    只有确认以后都不需要"定时任务"这个课程功能时才执行；表不在配置热更新指纹内，删表不会影响热更新。
-- ----------------------------------------------------------------------------
-- DROP TABLE IF EXISTS `ai_agent_task_schedule`;

-- ----------------------------------------------------------------------------
-- 6. 回滚：把备份行放回去（幂等，可整体执行）
-- ----------------------------------------------------------------------------
-- INSERT IGNORE INTO `ai_agent`               SELECT * FROM `bak_20261010_ai_agent`;
-- INSERT IGNORE INTO `ai_agent_flow_config`   SELECT * FROM `bak_20261010_ai_agent_flow_config`;
-- INSERT IGNORE INTO `ai_agent_task_schedule` SELECT * FROM `bak_20261010_ai_agent_task_schedule`;
-- INSERT IGNORE INTO `ai_client`              SELECT * FROM `bak_20261010_ai_client`;
-- INSERT IGNORE INTO `ai_client_config`       SELECT * FROM `bak_20261010_ai_client_config`;
-- INSERT IGNORE INTO `ai_client_model`        SELECT * FROM `bak_20261010_ai_client_model`;
-- INSERT IGNORE INTO `ai_client_advisor`      SELECT * FROM `bak_20261010_ai_client_advisor`;
-- INSERT IGNORE INTO `ai_client_system_prompt` SELECT * FROM `bak_20261010_ai_client_system_prompt`;
-- INSERT IGNORE INTO `ai_client_tool_mcp`     SELECT * FROM `bak_20261010_ai_client_tool_mcp`;
-- INSERT IGNORE INTO `ai_client_rag_order`    SELECT * FROM `bak_20261010_ai_client_rag_order`;
--
-- 确认回滚无误后可清理备份表：
-- DROP TABLE IF EXISTS `bak_20261010_ai_agent`, `bak_20261010_ai_agent_flow_config`,
--   `bak_20261010_ai_agent_task_schedule`, `bak_20261010_ai_client`, `bak_20261010_ai_client_config`,
--   `bak_20261010_ai_client_model`, `bak_20261010_ai_client_advisor`,
--   `bak_20261010_ai_client_system_prompt`, `bak_20261010_ai_client_tool_mcp`,
--   `bak_20261010_ai_client_rag_order`;
