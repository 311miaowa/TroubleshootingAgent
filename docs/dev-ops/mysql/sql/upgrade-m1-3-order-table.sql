-- ============================================================================
-- M1.3 升级脚本：MySQL 订单只读数据源
--
-- 内容：
--   1) ai_order 只读演示表 + 样例数据（与 group-buy-market 日志同日，便于后续跨源关联）
--   2) 只读账号 ai_ro（仅 SELECT）
--   3) ds_order_mysql 实体对齐：entity_code -> orders（语义工具名 query_orders）
--      + query_template 改用命名占位符 ${start}/${end}/${limit}
--
-- 幂等：建表/建用户用 IF NOT EXISTS；样例数据按 order_no 判存；UPDATE 为定值。
--
-- 执行方式：
--   $env:MYSQL_PWD='<你的 MySQL 口令>'
--   Get-Content -Raw -Encoding UTF8 upgrade-m1-3-order-table.sql |
--     & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' `
--       --host=127.0.0.1 --port=3306 --user=root --default-character-set=utf8mb4 ai-agent-station-study
--
-- 说明：ai_ro 的口令**不要写死在仓库里**：下面用占位口令 change-me-demo-pwd，
--       执行后请自行改成你自己的口令，并把运行时环境变量 DS_ORDER_MYSQL_PASSWORD 设为同一个值。
--       生产环境仅以环境变量/密钥管理方式注入，勿入库。
-- ============================================================================

-- 1. 只读订单表
CREATE TABLE IF NOT EXISTS `ai_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` varchar(64) NOT NULL COMMENT '订单号',
  `user_id` varchar(64) DEFAULT NULL COMMENT '用户ID',
  `status` varchar(32) DEFAULT NULL COMMENT '订单状态',
  `amount` decimal(10,2) DEFAULT NULL COMMENT '金额',
  `fail_reason` varchar(256) DEFAULT NULL COMMENT '失败原因',
  `create_time` datetime DEFAULT NULL COMMENT '下单时间',
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单只读表(M1.3 演示数据源)';

-- 2. 样例数据（幂等：按 order_no 判存）
INSERT INTO `ai_order` (`order_no`, `user_id`, `status`, `amount`, `fail_reason`, `create_time`)
SELECT * FROM (
  SELECT 'NO20260327001' AS order_no, 'user_test_001' AS user_id, 'FAILED' AS status, 199.00 AS amount, 'PAY_TIMEOUT' AS fail_reason, '2026-03-27 10:30:00' AS create_time
  UNION ALL SELECT 'NO20260327002', 'user_test_002', 'SUCCESS', 299.00, NULL, '2026-03-27 14:30:15'
  UNION ALL SELECT 'NO20260327003', 'user_test_003', 'FAILED', 399.00, 'INSUFFICIENT_BALANCE', '2026-03-27 15:00:00'
  UNION ALL SELECT 'NO20260327004', 'user_test_004', 'SUCCESS', 59.90, NULL, '2026-03-27 16:20:00'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM `ai_order` o WHERE o.order_no = seed.order_no);

-- 3. 只读账号（仅 SELECT；口令为占位值，执行后请改掉并把 DS_ORDER_MYSQL_PASSWORD 设为同值）
CREATE USER IF NOT EXISTS 'ai_ro'@'%' IDENTIFIED BY 'change-me-demo-pwd';
GRANT SELECT ON `ai-agent-station-study`.* TO 'ai_ro'@'%';
FLUSH PRIVILEGES;

-- 4. 实体对齐：entity_code=orders + 命名占位符 query_template
UPDATE `ai_data_source_entity`
SET `entity_code` = 'orders',
    `query_template` = 'SELECT order_no, user_id, status, amount, fail_reason, create_time FROM ai_order WHERE create_time >= ${start} AND create_time <= ${end} ORDER BY create_time DESC LIMIT ${limit}'
WHERE `ds_id` = 'ds_order_mysql' AND `entity_code` IN ('order', 'orders');

UPDATE `ai_data_source_field`
SET `entity_code` = 'orders'
WHERE `ds_id` = 'ds_order_mysql' AND `entity_code` IN ('order', 'orders');

-- 5. 校验
SELECT `ds_id`, `entity_code`, `physical_name`, `capability`, `query_template`
FROM `ai_data_source_entity` WHERE `ds_id` = 'ds_order_mysql';

SELECT COUNT(*) AS order_rows FROM `ai_order`;

SELECT `ds_id`, `entity_code`, `field_name`, `field_label`, `sensitive`
FROM `ai_data_source_field` WHERE `ds_id` = 'ds_order_mysql' ORDER BY `id`;

-- 6. 修正 conn_config：useSSL=false 时必须 allowPublicKeyRetrieval=true
--    （MySQL 8.0 默认 caching_sha2_password，否则 JDBC 报 "Public Key Retrieval is not allowed"）
UPDATE `ai_data_source`
SET `conn_config` = '{"jdbcUrl":"jdbc:mysql://127.0.0.1:3306/ai-agent-station-study?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true","username":"ai_ro","passwordEnv":"DS_ORDER_MYSQL_PASSWORD","driverClassName":"com.mysql.cj.jdbc.Driver"}'
WHERE `ds_id` = 'ds_order_mysql'
  AND `conn_config` NOT LIKE '%allowPublicKeyRetrieval%';
