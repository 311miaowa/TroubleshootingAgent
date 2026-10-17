-- ============================================================================
-- 将「对话模型」切换到 DeepSeek
--
-- 背景：
--   本项目的对话链路是数据库驱动的（装配时由 AiClientApiNode 读取 ai_client_api 并
--   构建 OpenAiApi → AiClientModelNode 构建 OpenAiChatModel），因此切换对话服务商
--   只需要改这两张表，无需改动任何 Java 代码。
--   DeepSeek 完全兼容 OpenAI 协议，可直接复用 OpenAiChatModel。
--
-- 关于向量化（RAG）：
--   DeepSeek 不提供 embeddings 接口，RAG 无法使用该 key。
--   本项目已把向量化拆成独立的 xfg.embedding.* 配置并默认关闭，
--   详见 application-dev.yml 与 AiAgentConfig、AiClientAdvisorNode 中的说明。
--
-- 执行前请先替换下面的 'sk-你的deepseek-api-key'。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 对话 API：指向 DeepSeek
-- ---------------------------------------------------------------------------
UPDATE `ai_client_api`
SET `base_url`         = 'https://api.deepseek.com',
    `api_key`          = 'sk-你的deepseek-api-key',
    `completions_path` = 'v1/chat/completions'
WHERE `api_id` = '1001';
-- embeddings_path 保持不变：DeepSeek 无 embeddings 接口，RAG 关闭时不会被调用

-- ---------------------------------------------------------------------------
-- 2. 对话模型：改为 deepseek-chat
--
--    必须用 deepseek-chat：deepseek-reasoner(R1) 不支持 function calling，
--    而本项目的 Agent 依赖 MCP 工具调用，用 R1 会导致工具无法被调用。
-- ---------------------------------------------------------------------------
UPDATE `ai_client_model`
SET `model_name` = 'deepseek-chat',
    `model_type` = 'deepseek'
WHERE `model_id` IN ('2001', '2002', '2003');


-- ---------------------------------------------------------------------------
-- 3. 校验
-- ---------------------------------------------------------------------------
SELECT '--- ai_client_api ---' AS section;
SELECT `api_id`, `base_url`, `completions_path`,
       CONCAT(LEFT(`api_key`, 6), '****') AS api_key_masked
FROM `ai_client_api`;

SELECT '--- ai_client_model ---' AS section;
SELECT `model_id`, `api_id`, `model_name`, `model_type`, `status`
FROM `ai_client_model`;

-- ---------------------------------------------------------------------------
-- 回滚（如需切回原服务商，把下面注释打开并把值改回）
-- ---------------------------------------------------------------------------
-- UPDATE `ai_client_api`
-- SET `base_url` = 'https://api.deepseek.com',
--     `api_key`  = 'sk-原key',
--     `completions_path` = 'v1/chat/completions'
-- WHERE `api_id` = '1001';
--
-- UPDATE `ai_client_model` SET `model_name` = 'gpt-4.1-mini' WHERE `model_id` = '2001';
-- UPDATE `ai_client_model` SET `model_name` = 'gpt-5-mini'   WHERE `model_id` IN ('2002','2003');
