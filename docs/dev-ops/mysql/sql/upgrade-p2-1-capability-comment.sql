-- ============================================================================
-- P2 收敛：把两张表的 capability 列注释对齐到 DataSourceCapability.key()
-- ----------------------------------------------------------------------------
-- 背景：列注释原本写着"能力(entity_meta/log_search/entity_query)"，
--       之后陆续加了 anomaly_summary(M7a)、cross_source(P1)、fault_window(M8)，
--       注释没跟上 → 看库的人会以为只有三种能力。
--
-- 幂等：MODIFY COLUMN 只改定义与注释，不改数据；可重复执行。
-- ⚠️ 注意：MODIFY COLUMN 是"用给定定义整体替换"，会把列定义**重置成本文件里写的形态**。
--    本文件按建表语句（upgrade-m1-0-datasource-tables.sql:62/:103）写的 varchar(32) NOT NULL；
--    若线上该列的长度/DEFAULT/COLLATE 被人改过，会在这里被改回去。执行前先核对：
--      SHOW COLUMNS FROM `ai_data_source_entity` LIKE 'capability';
--      SHOW COLUMNS FROM `ai_stage_tool_policy`  LIKE 'capability';
--
-- 执行（**中文注释务必走原生重定向**，否则会双重编码成乱码）：
--   cmd:  mysql --default-character-set=utf8mb4 -h127.0.0.1 -P3306 -uroot -p "ai-agent-station-study" < upgrade-p2-1-capability-comment.sql
--   bash: mysql --default-character-set=utf8mb4 -h127.0.0.1 -P3306 -uroot -p "ai-agent-station-study" < upgrade-p2-1-capability-comment.sql
--   ❌ 不要用 PowerShell 的 `Get-Content -Raw | mysql.exe`：PS 5.1 会按 GBK 解码 UTF-8 文件、
--      再按 $OutputEncoding 转码一次，中文变成"鑳藉姏"这类双重编码（库里存的就是乱码字节）。
-- 校验：SELECT LEFT(HEX(COLUMN_COMMENT),12) 应为 E883BDE58A9B（= "能力" 的 6 个 UTF-8 字节）；
--       LEFT(...,12) 是 12 个 hex 字符，对应 6 字节。写 18 会取到 9 字节（多出全角括号），必然对不上。
-- 每条 ALTER 刻意压成**单行**：多行语句经管道喂给 mysql 时容易被截断。
-- ============================================================================

USE `ai-agent-station-study`;

ALTER TABLE `ai_data_source_entity` MODIFY COLUMN `capability` varchar(32) NOT NULL COMMENT '能力（与 DataSourceCapability.key() 一致，均为只读）：entity_meta/entity_query/log_search/anomaly_summary/cross_source/fault_window';

ALTER TABLE `ai_stage_tool_policy` MODIFY COLUMN `capability` varchar(32) NOT NULL COMMENT '能力（与 DataSourceCapability.key() 一致）：entity_meta/entity_query/log_search/anomaly_summary/cross_source/fault_window';

-- 自检 1：应看到两条带完整能力清单的注释
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'capability' ORDER BY TABLE_NAME;

-- 自检 2：注释首 6 字节应为"能力"（E883BDE58A9B）
SELECT COLUMN_NAME, LEFT(HEX(COLUMN_COMMENT), 12) AS head_hex, LEFT(COLUMN_COMMENT, 2) AS head_text FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'capability' ORDER BY TABLE_NAME;

-- ---------------------------------------------------------------------------
-- ROLLBACK（把注释改回原样；同样只需单行 ALTER）
-- ---------------------------------------------------------------------------
-- ALTER TABLE `ai_data_source_entity` MODIFY COLUMN `capability` varchar(32) NOT NULL COMMENT '能力(entity_query/log_search)，均为只读';
-- ALTER TABLE `ai_stage_tool_policy`  MODIFY COLUMN `capability` varchar(32) NOT NULL COMMENT '能力(entity_meta/log_search/entity_query)';
