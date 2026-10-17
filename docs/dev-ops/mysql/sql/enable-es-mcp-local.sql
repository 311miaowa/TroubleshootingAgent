-- ============================================================================
-- 启用「本地 ES MCP」工具（限流排查核心场景）
--
-- 为什么需要这个脚本：
--   仓库里 ai_client_tool_mcp 与 ai_client_config 的初始数据中，所有 MCP 工具
--   都是禁用状态（status=0）。因此仅部署代码，Agent 虽然能对话，但**一个工具都没有**，
--   “ES 定位根因”这条主线是不可用的。本脚本把本地 ES MCP 启用起来。
--
-- 为什么只启用 5007：
--   5007 = localhost:9200 上的 ES MCP，本地依赖、可控；
--   5006（baidu-search）等为外网依赖，不建议在排障回归链路里启用。
--
-- 前置条件：
--   1) Elasticsearch 已启动（默认 http://127.0.0.1:9200），且索引里有可查的日志数据；
--   2) 本机 Node.js 可用（ES MCP 通过 npx 以 stdio 方式拉起）。
--
-- 幂等：可重复执行。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 修正 MCP 启动命令（Windows 必需）
--
--    Java 的 ProcessBuilder 在 Windows 上无法直接拉起裸 `npx`（会抛
--    CreateProcess error=2），必须用 `npx.cmd`。此处仅在当前值是裸 npx 时替换，
--    已是绝对路径或已替换过则不动。
--    Linux / macOS 不需要这一步，执行后面的 UPDATE 前请先确认第 4 步的查询结果。
-- ---------------------------------------------------------------------------
UPDATE `ai_client_tool_mcp`
SET `transport_config` = REPLACE(`transport_config`, '"command": "npx"', '"command": "npx.cmd"')
WHERE `mcp_id` = '5007'
  AND `transport_config` LIKE '%"command": "npx"%';

-- 可选：改用绝对路径更稳（不依赖 PATH 解析），把下面的路径换成你机器上的实际位置
-- UPDATE `ai_client_tool_mcp`
-- SET `transport_config` = JSON_SET(`transport_config`,
--         '$."elasticsearch-mcp-server".command', 'D:\\node\\npx.cmd')
-- WHERE `mcp_id` = '5007';

-- ---------------------------------------------------------------------------
-- 2. 启用 ES MCP 工具本身
-- ---------------------------------------------------------------------------
UPDATE `ai_client_tool_mcp`
SET `status` = 1
WHERE `mcp_id` = '5007';

-- ---------------------------------------------------------------------------
-- 3. 启用「模型/客户端 → 工具」的绑定
--
--    注意：模型级（ai_client_model 关联）与客户端级（ai_client 关联）两条路径
--    装配时都会被读取，必须一起启用，否则部分阶段的 Agent 拿不到工具。
-- ---------------------------------------------------------------------------
UPDATE `ai_client_config`
SET `status` = 1
WHERE `target_type` = 'tool_mcp'
  AND `target_id` = '5007';

-- ---------------------------------------------------------------------------
-- 4. 校验
-- ---------------------------------------------------------------------------
SELECT '--- 5007 状态与启动命令 ---' AS section;
SELECT `mcp_id`,
       `mcp_name`,
       `status`,
       JSON_EXTRACT(`transport_config`, '$.*.command') AS command
FROM `ai_client_tool_mcp`
WHERE `mcp_id` = '5007';

SELECT '--- 已启用的 5007 绑定 ---' AS section;
SELECT id, `source_type`, `source_id`, `target_type`, `target_id`, `status`
FROM `ai_client_config`
WHERE `target_type` = 'tool_mcp' AND `target_id` = '5007'
ORDER BY id;

-- 期望：应用启动日志出现
--   Tool Stdio MCP Initialized InitializeResult[... serverInfo=Implementation[name=mcp-server-elasticsearch ...]]
-- 且不再出现
--   没有需要被初始化的 ai client tool mcp

-- ---------------------------------------------------------------------------
-- 5. 安全建议：移除无用的 ES_API_KEY
--
--    默认的 docker-compose ELK 未启用安全特性（ES 启动时会自行提示
--    "security features are not enabled. Without authentication..."），
--    此时 ES_API_KEY 不仅无用，还把凭据留在了库表里。
--    注意：不要再把整库 mysqldump 提交进仓库（历史目录 docs/dev-ops/sql-bak/ 已随二开清理删除），
--    否则会把凭据带进仓库。
-- ---------------------------------------------------------------------------
-- UPDATE `ai_client_tool_mcp`
-- SET `transport_config` = JSON_REMOVE(`transport_config`,
--         '$."elasticsearch-mcp-server".env.ES_API_KEY')
-- WHERE `mcp_id` = '5007'
--   AND JSON_EXTRACT(`transport_config`, '$."elasticsearch-mcp-server".env.ES_API_KEY') IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 回滚：恢复为本仓库初始数据的「全部禁用」状态
-- ---------------------------------------------------------------------------
-- UPDATE `ai_client_tool_mcp` SET `status` = 0 WHERE `mcp_id` = '5007';
-- UPDATE `ai_client_config`   SET `status` = 0 WHERE `target_type` = 'tool_mcp' AND `target_id` = '5007';
