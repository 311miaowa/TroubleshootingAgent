-- ============================================================================
-- P1-2 / D-6：PostgreSQL 只读账号（幂等，可重复执行）
-- ----------------------------------------------------------------------------
-- 目标库：ai-rag-knowledge（pgvector 容器 vector_db，宿主机 127.0.0.1:15432）
-- 目标账号：ai_ro_pg（对齐 MySQL 侧已有的 ai_ro 约定）
--
-- 为什么需要它
--   应用层只发 SELECT，但那是**应用层**的约束；服务端没有只读角色时，
--   一旦应用层被绕过（注入、代码回归、别的客户端复用同一账号），写入会真的落库。
--
-- 执行方式（口令只通过 psql 变量传入，**不写进仓库**）
--   $env:DS_PG_RO_PASSWORD = '<强口令>'
--   docker exec -e PGPASSWORD=$env:PGVECTOR_PASSWORD vector_db `
--     psql -U postgres -d "ai-rag-knowledge" -v ro_password="$env:DS_PG_RO_PASSWORD" `
--     -f /path/to/upgrade-p1-4-pg-readonly-role.sql
--
-- 注意
--   1) 角色与权限是**库级**的，切库要重跑（本文件只针对 ai-rag-knowledge）；
--   2) `ALTER DEFAULT PRIVILEGES` 只影响**执行该语句的角色**将来创建的表，
--      所以务必用建表的属主（这里是 postgres）执行；
--   3) 回滚段在文件末尾，默认注释掉。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 建角色（幂等：已存在则只重置口令，保证口令可轮换）
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_ro_pg') THEN
        CREATE ROLE ai_ro_pg LOGIN;
    END IF;
END
$$;

-- 口令来自 psql 变量 :'ro_password'（不要把明文写进仓库）
ALTER ROLE ai_ro_pg WITH LOGIN PASSWORD :'ro_password';

-- 只读角色不需要这些能力（防御性显式收回）
ALTER ROLE ai_ro_pg NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

-- ---------------------------------------------------------------------------
-- 2) 库 / schema / 表权限
-- ---------------------------------------------------------------------------
GRANT CONNECT ON DATABASE "ai-rag-knowledge" TO ai_ro_pg;
GRANT USAGE  ON SCHEMA public TO ai_ro_pg;

GRANT SELECT ON ALL TABLES    IN SCHEMA public TO ai_ro_pg;

-- 将来由 postgres 新建的表也自动只读（执行者必须是建表属主）
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO ai_ro_pg;

-- 防御性撤销写权限：即使有人误授，这里也再收一次
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public FROM ai_ro_pg;

-- ---------------------------------------------------------------------------
-- 3) 自检（应为 0 rows：该角色没有任何写权限）
-- ---------------------------------------------------------------------------
SELECT grantee, table_name, privilege_type
FROM information_schema.role_table_grants
WHERE grantee = 'ai_ro_pg'
  AND privilege_type IN ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')
ORDER BY 1, 2, 3;

-- 应为 N 行（public schema 下每张表的 SELECT）
SELECT count(*) AS readonly_table_count
FROM information_schema.role_table_grants
WHERE grantee = 'ai_ro_pg' AND privilege_type = 'SELECT';

-- ---------------------------------------------------------------------------
-- 4) 应用侧切换（MySQL 元数据，**本条不在 psql 里执行**，列出供核对）
-- ---------------------------------------------------------------------------
-- UPDATE ai_data_source
--    SET conn_config = JSON_SET(conn_config,
--          '$.username', 'ai_ro_pg',
--          '$.passwordEnv', 'DS_PG_RO_PASSWORD')
--  WHERE ds_id = 'ds_pg_payment';
--
-- 回滚（MySQL 侧）：
-- UPDATE ai_data_source
--    SET conn_config = JSON_SET(conn_config,
--          '$.username', 'postgres',
--          '$.passwordEnv', 'PGVECTOR_PASSWORD')
--  WHERE ds_id = 'ds_pg_payment';

-- ---------------------------------------------------------------------------
-- 5) ROLLBACK（默认注释，需要时手工执行）
-- ---------------------------------------------------------------------------
-- REVOKE ALL ON ALL TABLES IN SCHEMA public FROM ai_ro_pg;
-- ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES FROM ai_ro_pg;
-- REVOKE USAGE ON SCHEMA public FROM ai_ro_pg;
-- REVOKE CONNECT ON DATABASE "ai-rag-knowledge" FROM ai_ro_pg;
-- DROP ROLE IF EXISTS ai_ro_pg;
