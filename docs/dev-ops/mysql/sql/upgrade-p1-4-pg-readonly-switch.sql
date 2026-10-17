-- ============================================================================
-- P1-2 / D-6：把 PG 数据源切换到只读账号 ai_ro_pg（幂等，可重复执行）
-- ----------------------------------------------------------------------------
-- 前置：先执行 docs/dev-ops/postgres/sql/upgrade-p1-4-pg-readonly-role.sql
--      并在部署环境注入环境变量 DS_PG_RO_PASSWORD（与角色口令一致）。
--
-- 为什么必须切配置
--   只建角色不改 conn_config = 应用仍用可写账号连库，服务端权限不会生效。
--
-- 回滚：把 username / passwordEnv 还原成 postgres / PGVECTOR_PASSWORD（见文件末尾）
-- ============================================================================

-- 目标库：ai-agent-station-study（本项目的元数据库）
USE `ai-agent-station-study`;

UPDATE ai_data_source
   SET conn_config = JSON_SET(conn_config,
         '$.username',    'ai_ro_pg',
         '$.passwordEnv', 'DS_PG_RO_PASSWORD')
 WHERE ds_id = 'ds_pg_payment';

-- 自检：应看到 username=ai_ro_pg、passwordEnv=DS_PG_RO_PASSWORD
SELECT ds_id, conn_config
  FROM ai_data_source
 WHERE ds_id = 'ds_pg_payment';

-- ---------------------------------------------------------------------------
-- ROLLBACK（需要时手工执行）
-- ---------------------------------------------------------------------------
-- UPDATE ai_data_source
--    SET conn_config = JSON_SET(conn_config,
--          '$.username',    'postgres',
--          '$.passwordEnv', 'PGVECTOR_PASSWORD')
--  WHERE ds_id = 'ds_pg_payment';
