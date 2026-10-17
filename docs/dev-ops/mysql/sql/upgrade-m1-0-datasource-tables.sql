-- ============================================================================
-- M1.0 升级脚本：统一数据源接入层 —— 4 张新表 + 种子数据
--
-- 设计依据：docs/dev-ops/M1-unified-datasource-design.md（第 4 节 DDL、第 9 节 M1.0）
--
-- 本次只建"配置与字典"相关的新表，【不改任何既有表】，因此对现有功能零行为变更。
--
-- 4 张表：
--   ai_data_source          数据源实例（ES 走 mcp_id，MySQL 走 conn_config）
--   ai_data_source_entity   实体（order/log…），capacity 全为只读
--   ai_data_source_field    字段字典（供生成工具 schema + 脱敏 + 字段裁剪）
--   ai_stage_tool_policy    阶段-工具能力策略（解决"四步链被短路"的关键配置表）
--
-- 幂等性：
--   建表用 CREATE TABLE IF NOT EXISTS；
--   种子数据逐条用 information_schema 判存 + PREPARE/EXECUTE 保护，可重复执行。
--   （不依赖 INSERT IGNORE，避免"已存在但内容被手工改过"时被静默跳过而看不出来。）
--
-- ⚠️ 热更新注意（M1.1 才接线，本步不生效）：
--   这 4 张表需要在 M1.1 加入 AiClientConfigRefreshScheduler.queryConfigVersion() 的 UNION，
--   否则改配置不会触发热更新。本脚本先不承担该改动。
--
-- 执行方式（密码用环境变量，避免 mysql 的 stderr 警告被当成终止错误）：
--   $env:MYSQL_PWD='<你的 MySQL 口令>'
--   mysql -h127.0.0.1 -P3306 -uroot ai-agent-station-study < upgrade-m1-0-datasource-tables.sql
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. 数据源实例
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_data_source` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` varchar(64) NOT NULL COMMENT '数据源ID',
  `ds_name` varchar(64) NOT NULL COMMENT '数据源名称',
  `ds_type` varchar(32) NOT NULL COMMENT '类型(mysql/elasticsearch/loki/clickhouse)',
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户标识',
  `mcp_id` varchar(64) DEFAULT NULL COMMENT '经MCP接入时对应的ai_client_tool_mcp.mcp_id',
  `conn_config` varchar(2048) DEFAULT NULL COMMENT '连接配置(JSON,不含明文密码)',
  `time_field` varchar(64) DEFAULT NULL COMMENT '默认时间字段',
  `max_rows` int NOT NULL DEFAULT 200 COMMENT '单次结果行数硬上限',
  `timeout_ms` int NOT NULL DEFAULT 5000 COMMENT '查询超时(毫秒)',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态(0:禁用,1:启用)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_id` (`ds_id`),
  KEY `idx_tenant_status` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据源配置表';


-- ----------------------------------------------------------------------------
-- 2. 数据源实体
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_data_source_entity` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` varchar(64) NOT NULL COMMENT '数据源ID',
  `entity_code` varchar(64) NOT NULL COMMENT '实体编码(order/log)',
  `entity_name` varchar(64) NOT NULL COMMENT '实体名称',
  `entity_desc` varchar(512) DEFAULT NULL COMMENT '实体描述(注入给模型)',
  `physical_name` varchar(128) NOT NULL COMMENT '物理定位(表名/索引模式)',
  `capability` varchar(32) NOT NULL COMMENT '能力（与 DataSourceCapability.key() 一致，均为只读）：entity_meta/entity_query/log_search/anomaly_summary/cross_source/fault_window',
  `query_template` text COMMENT '参数化查询模板(禁止模型拼查询语句)',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态(0:禁用,1:启用)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_entity` (`ds_id`,`entity_code`),
  KEY `idx_capability` (`capability`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据源实体配置表';


-- ----------------------------------------------------------------------------
-- 3. 数据源字段字典
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_data_source_field` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` varchar(64) NOT NULL COMMENT '数据源ID',
  `entity_code` varchar(64) NOT NULL COMMENT '实体编码',
  `field_name` varchar(64) NOT NULL COMMENT '字段名',
  `field_label` varchar(64) DEFAULT NULL COMMENT '中文名',
  `field_type` varchar(32) DEFAULT NULL COMMENT '字段类型',
  `is_time_field` tinyint(1) DEFAULT '0' COMMENT '是否时间字段',
  `is_filterable` tinyint(1) DEFAULT '1' COMMENT '是否可过滤',
  `sample_value` varchar(256) DEFAULT NULL COMMENT '样例值',
  `sensitive` tinyint(1) DEFAULT '0' COMMENT '是否需脱敏',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态(0:禁用,1:启用)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_entity_field` (`ds_id`,`entity_code`,`field_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据源字段字典表';


-- ----------------------------------------------------------------------------
-- 4. 阶段-工具能力策略
--    stage_code = ai_agent_flow_config.client_type（与 AiClientTypeEnumVO 取值一致）
--    allowed 默认 0（默认拒绝），只有显式置 1 的能力才会暴露给该阶段
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_stage_tool_policy` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stage_code` varchar(64) NOT NULL COMMENT '阶段=ai_agent_flow_config.client_type，如 TASK_ANALYZER_CLIENT',
  `capability` varchar(32) NOT NULL COMMENT '能力（与 DataSourceCapability.key() 一致）：entity_meta/entity_query/log_search/anomaly_summary/cross_source/fault_window',
  `allowed` tinyint(1) DEFAULT '0' COMMENT '是否允许(0:否,1:是)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stage_capability` (`stage_code`,`capability`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='阶段-工具能力策略表';


-- ============================================================================
-- 5. 种子数据
-- ============================================================================

-- 5.1 数据源实例（两个源：ES 日志 + MySQL 订单）
SET @exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_data_source');
SET @sql := IF(@exists > 0,
  'INSERT INTO `ai_data_source`
     (`ds_id`, `ds_name`, `ds_type`, `tenant_id`, `mcp_id`, `conn_config`, `time_field`, `max_rows`, `timeout_ms`, `status`)
   SELECT * FROM (SELECT
     ''ds_log_es'' AS ds_id,
     ''ELK 日志源'' AS ds_name,
     ''elasticsearch'' AS ds_type,
     ''default'' AS tenant_id,
     ''5007'' AS mcp_id,
     NULL AS conn_config,
     ''@timestamp'' AS time_field,
     200 AS max_rows,
     5000 AS timeout_ms,
     1 AS status
   ) AS seed
   WHERE NOT EXISTS (SELECT 1 FROM `ai_data_source` WHERE ds_id = ''ds_log_es'')',
  'SELECT ''ai_data_source 不存在，跳过种子数据'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(@exists > 0,
  'INSERT INTO `ai_data_source`
     (`ds_id`, `ds_name`, `ds_type`, `tenant_id`, `mcp_id`, `conn_config`, `time_field`, `max_rows`, `timeout_ms`, `status`)
   SELECT * FROM (SELECT
     ''ds_order_mysql'' AS ds_id,
     ''订单库(只读)'' AS ds_name,
     ''mysql'' AS ds_type,
     ''default'' AS tenant_id,
     NULL AS mcp_id,
     ''{"jdbcUrl":"jdbc:mysql://127.0.0.1:3306/ai-agent-station-study?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false","username":"ai_ro","passwordEnv":"DS_ORDER_MYSQL_PASSWORD","driverClassName":"com.mysql.cj.jdbc.Driver"}'' AS conn_config,
     ''create_time'' AS time_field,
     200 AS max_rows,
     5000 AS timeout_ms,
     1 AS status
   ) AS seed
   WHERE NOT EXISTS (SELECT 1 FROM `ai_data_source` WHERE ds_id = ''ds_order_mysql'')',
  'SELECT ''ai_data_source 不存在，跳过种子数据'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5.2 实体（log_search + entity_query，均只读）
SET @exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_data_source_entity');
SET @sql := IF(@exists > 0,
  'INSERT INTO `ai_data_source_entity`
     (`ds_id`, `entity_code`, `entity_name`, `entity_desc`, `physical_name`, `capability`, `query_template`, `status`)
   SELECT * FROM (SELECT
     ''ds_log_es'' AS ds_id,
     ''logs'' AS entity_code,
     ''应用日志'' AS entity_name,
     ''ELK 中的应用日志。按关键字 + 时间窗检索原始日志行，用于定位报错与异常堆栈。'' AS entity_desc,
     ''group-buy-market-log-*'' AS physical_name,
     ''log_search'' AS capability,
     ''{"query":{"bool":{"must":[{"range":{"@timestamp":{"gte":"${start}","lte":"${end}"}}},{"match":{"message":"${keyword}"}}]}},"sort":[{"@timestamp":{"order":"desc"}}],"size":${size}}'' AS query_template,
     1 AS status
   ) AS seed
   WHERE NOT EXISTS (SELECT 1 FROM `ai_data_source_entity` WHERE ds_id = ''ds_log_es'' AND entity_code = ''log'')',
  'SELECT ''ai_data_source_entity 不存在，跳过种子数据'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(@exists > 0,
  'INSERT INTO `ai_data_source_entity`
     (`ds_id`, `entity_code`, `entity_name`, `entity_desc`, `physical_name`, `capability`, `query_template`, `status`)
   SELECT * FROM (SELECT
     ''ds_order_mysql'' AS ds_id,
     ''order'' AS entity_code,
     ''订单'' AS entity_name,
     ''订单主表。按订单号 / 时间窗查询订单状态、金额、失败原因，用于核对"用户下单失败"类问题。'' AS entity_desc,
     ''ai_order'' AS physical_name,
     ''entity_query'' AS capability,
     ''SELECT order_no, user_id, status, amount, fail_reason, create_time FROM ai_order WHERE create_time >= ? AND create_time <= ? AND (order_no = ? OR ? IS NULL) ORDER BY create_time DESC LIMIT ?'' AS query_template,
     1 AS status
   ) AS seed
   WHERE NOT EXISTS (SELECT 1 FROM `ai_data_source_entity` WHERE ds_id = ''ds_order_mysql'' AND entity_code = ''order'')',
  'SELECT ''ai_data_source_entity 不存在，跳过种子数据'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5.3 字段字典（log 索引 + order 表；user_id 标记 sensitive 用于验证脱敏链路）
SET @exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_data_source_field');
SET @sql := IF(@exists > 0,
  'INSERT INTO `ai_data_source_field`
     (`ds_id`, `entity_code`, `field_name`, `field_label`, `field_type`, `is_time_field`, `is_filterable`, `sample_value`, `sensitive`, `status`)
   SELECT * FROM (
     SELECT ''ds_log_es'' AS `ds_id`, ''logs'' AS `entity_code`, ''@timestamp'' AS `field_name`, ''日志时间'' AS `field_label`, ''date'' AS `field_type`, 1 AS `is_time_field`, 1 AS `is_filterable`, ''2026-03-27T15:00:00+08:00'' AS `sample_value`, 0 AS `sensitive`, 1 AS `status`
     UNION ALL SELECT ''ds_log_es'', ''logs'', ''level'', ''日志级别'', ''keyword'', 0, 1, ''INFO'', 0, 1
     UNION ALL SELECT ''ds_log_es'', ''logs'', ''message'', ''日志内容'', ''text'', 0, 0, ''测试日志'', 0, 1
     UNION ALL SELECT ''ds_log_es'', ''logs'', ''application'', ''应用名'', ''keyword'', 0, 1, ''test'', 0, 1
     UNION ALL SELECT ''ds_order_mysql'', ''order'', ''order_no'', ''订单号'', ''varchar'', 0, 1, ''NO20250627001'', 0, 1
     UNION ALL SELECT ''ds_order_mysql'', ''order'', ''user_id'', ''用户ID'', ''varchar'', 0, 1, ''U10086'', 1, 1
     UNION ALL SELECT ''ds_order_mysql'', ''order'', ''status'', ''订单状态'', ''varchar'', 0, 1, ''FAILED'', 0, 1
     UNION ALL SELECT ''ds_order_mysql'', ''order'', ''amount'', ''金额'', ''decimal'', 0, 1, ''199.00'', 0, 1
     UNION ALL SELECT ''ds_order_mysql'', ''order'', ''fail_reason'', ''失败原因'', ''varchar'', 0, 1, ''PAY_TIMEOUT'', 0, 1
     UNION ALL SELECT ''ds_order_mysql'', ''order'', ''create_time'', ''下单时间'', ''datetime'', 1, 1, ''2025-06-27 18:29:00'', 0, 1
   ) AS seed
   WHERE NOT EXISTS (
     SELECT 1 FROM `ai_data_source_field` f
     WHERE f.ds_id = seed.ds_id AND f.entity_code = seed.entity_code AND f.field_name = seed.field_name
   )',
  'SELECT ''ai_data_source_field 不存在，跳过种子数据'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5.4 阶段-工具能力策略
--     分析/监督阶段只给 entity_meta（了解有哪些实体），不给执行类能力 → 四步链才走得完整
SET @exists := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_stage_tool_policy');
SET @sql := IF(@exists > 0,
  'INSERT INTO `ai_stage_tool_policy` (stage_code, capability, allowed)
   SELECT * FROM (
     SELECT ''TASK_ANALYZER_CLIENT'' AS stage_code, ''entity_meta'' AS capability, 1 AS allowed
     UNION ALL SELECT ''TASK_ANALYZER_CLIENT'', ''log_search'', 0
     UNION ALL SELECT ''TASK_ANALYZER_CLIENT'', ''entity_query'', 0
     UNION ALL SELECT ''PRECISION_EXECUTOR_CLIENT'', ''entity_meta'', 1
     UNION ALL SELECT ''PRECISION_EXECUTOR_CLIENT'', ''log_search'', 1
     UNION ALL SELECT ''PRECISION_EXECUTOR_CLIENT'', ''entity_query'', 1
     UNION ALL SELECT ''QUALITY_SUPERVISOR_CLIENT'', ''entity_meta'', 1
     UNION ALL SELECT ''QUALITY_SUPERVISOR_CLIENT'', ''log_search'', 0
     UNION ALL SELECT ''QUALITY_SUPERVISOR_CLIENT'', ''entity_query'', 0
     UNION ALL SELECT ''RESPONSE_ASSISTANT'', ''entity_meta'', 0
     UNION ALL SELECT ''RESPONSE_ASSISTANT'', ''log_search'', 0
     UNION ALL SELECT ''RESPONSE_ASSISTANT'', ''entity_query'', 0
   ) AS seed
   WHERE NOT EXISTS (
     SELECT 1 FROM `ai_stage_tool_policy` p
     WHERE p.stage_code = seed.stage_code AND p.capability = seed.capability
   )',
  'SELECT ''ai_stage_tool_policy 不存在，跳过种子数据'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ============================================================================
-- 6. 自检（应输出 4 张表，以及种子数据条数 2 / 2 / 12 / 12）
-- ============================================================================
SELECT 'ai_data_source' AS tbl, COUNT(*) AS rows_cnt FROM `ai_data_source`
UNION ALL SELECT 'ai_data_source_entity', COUNT(*) FROM `ai_data_source_entity`
UNION ALL SELECT 'ai_data_source_field', COUNT(*) FROM `ai_data_source_field`
UNION ALL SELECT 'ai_stage_tool_policy', COUNT(*) FROM `ai_stage_tool_policy`;


-- ============================================================================
-- 7. 回滚（需要时手工执行；新表无外部依赖，DROP 即可）
-- ============================================================================
-- DROP TABLE IF EXISTS `ai_stage_tool_policy`;
-- DROP TABLE IF EXISTS `ai_data_source_field`;
-- DROP TABLE IF EXISTS `ai_data_source_entity`;
-- DROP TABLE IF EXISTS `ai_data_source`;
