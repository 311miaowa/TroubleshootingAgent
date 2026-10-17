# 事实核查：ES MCP 的 6 个写/删类工具是否真的暴露给模型？

**结论：不暴露（潜在风险，非现行暴露）。** 以下每条都附可复现命令与实际输出。

核查时间：2026-09-26 · 分支 `feat/p1-3-timeline-view` @ `94478d5`（= V1 `3f7c6fc` + 3 个提交）

---

## 1. ES MCP server 真实暴露的工具清单（已实测）

**方法**：直连 stdio MCP server，走 `initialize` → `tools/list`。**只握手 + 列工具，不调用任何工具**，无副作用。

```js
// 用 D:\node\npx.cmd 起 @awesome-ai/elasticsearch-mcp，ES_HOST=http://127.0.0.1:9200
// 发 {"method":"initialize"} 后发 {"method":"tools/list"}
```

**实际输出**：

```
INITIALIZE_OK serverInfo={"name":"mcp-server-elasticsearch","version":"1.0.0"}
TOOL_COUNT=11
TOOL: list_indices
TOOL: get_mappings
TOOL: search
TOOL: elasticsearch_health
TOOL: create_index
TOOL: create_mapping
TOOL: bulk
TOOL: reindex
TOOL: create_index_template
TOOL: get_index_template
TOOL: delete_index_template
```

**机械事实**：11 个工具 = **读 5 个**（`list_indices`/`get_mappings`/`search`/`elasticsearch_health`/`get_index_template`）
+ **写/删 6 个**（`create_index`/`create_mapping`/`bulk`/`reindex`/`create_index_template`/`delete_index_template`）。

> 交接文档 `M4-datasource-health-design.md` §P1 与 `M4-status-and-next-tasks.md` §4.2 里点名的 6 个工具名
> **与实测完全一致**——这份描述本身是准确的。

## 2. 库表现状（已实测，mcp 5007 处于启用状态）

```sql
SELECT id, source_type, source_id, target_type, target_id, status
FROM ai_client_config WHERE target_type='tool_mcp' ORDER BY source_type, source_id;
```

```
| 28 | client | 4101 | tool_mcp | 5007 | 1 |
| 32 | client | 4102 | tool_mcp | 5007 | 1 |
| 36 | client | 4103 | tool_mcp | 5007 | 1 |
| 24 | model  | 2002 | tool_mcp | 5007 | 1 |
| 39 | model  | 2003 | tool_mcp | 5007 | 1 |
```

`ai_client_tool_mcp` 中 `mcp_id=5007` `status=1`，`transport_type=stdio`，
`JSON_EXTRACT(transport_config,'$.*.command')` = `["D:\\node\\npx.cmd"]`（Windows 必需，已修过）。

**机械事实**：7 条绑定 `status=1`，且 5007 自身 `status=1`。即"配置层面是开着的"。

## 3. 运行时事实（决定性证据：MCP 连上了，但工具没给模型）

起后端（8098，日志落盘）：

```
java -Djava.io.tmpdir=<repo>\data\tmp -jar ai-agent-station-study-app.jar --server.port=8098
```

**日志中 MCP 确实初始化成功**：

```
AiClientToolMcpNode - Tool Stdio MCP Initialized ... serverInfo=Implementation[name=mcp-server-elasticsearch, version=1.0.0]
BeanRegistry        - 成功注册Bean: ai_client_tool_mcp_5007
```

**但同一份日志里，实际交给 ChatClient 的工具清单（`DataSourceToolCallbackFactory` 全量日志）不含任何 MCP 工具**：

| clientId | clientType | 工具清单 |
|---|---|---|
| 3001 | — | `[]` |
| 3101 | TASK_ANALYZER_CLIENT | `[describe_entity]` |
| 3102 | PRECISION_EXECUTOR_CLIENT | `[query_logs, query_orders, query_payments, describe_entity, query_correlated_timeline]` |
| 3103 | QUALITY_SUPERVISOR_CLIENT | `[describe_entity]` |
| 3104 | RESPONSE_ASSISTANT | `[]` |
| 4101 | TASK_ANALYZER_CLIENT | `[describe_entity]` |
| 4102 | PRECISION_EXECUTOR_CLIENT | `[query_logs, query_orders, query_payments, describe_entity, query_correlated_timeline]` |
| 4103 | QUALITY_SUPERVISOR_CLIENT | `[describe_entity]` |
| 4104 | RESPONSE_ASSISTANT | `[]` |

**9 个客户端的工具清单里，MCP 工具数 = 0。**

全日志检索 6 个写/删工具名 + `tools/list`：

```
grep -c 'create_index|create_mapping|bulk|reindex|create_index_template|delete_index_template' → 13 命中，
  但逐条检查全部是 'elasticsearch' / '_search' / 'DataSourceHealthService' 的子串误命中，无一是工具名
grep -c 'tools/list|ListTools|listTools' → 0
```

**机械事实**：MCP 客户端进程被拉起、握手成功、Bean 注册成功；但**从未调用过 `tools/list`**，
也从未把任何 MCP 工具放进任何 ChatClient。

## 4. 静态事实（代码层，可复现）

| 检查 | 命令 | 结果 |
|---|---|---|
| `getMcpBeanNameList()` 调用方 | `grep -rn 'getMcpBeanNameList' --include=*.java` | **仅 1 处 = 其定义处**（`AiClientVO.java:63`），**零调用方** |
| 生产代码是否用 `SyncMcpToolCallbackProvider` | `grep -rn 'SyncMcpToolCallbackProvider'`（排除 `src/test`） | **main 树零命中**；18 处全在 `ai-agent-station-study-app/src/test/**`（6 个测试类） |
| 模型级是否注入 MCP 工具 | `AiClientModelNode.java:21-26, 58` | 注释明写 M1.1 已**移除**模型级 MCP 工具注入；代码只 `OpenAiChatModel.builder()...build()`，无工具 |
| 客户端级工具来源 | `AiClientNode.java:63-64, 89` | 只用 `dataSourceToolCallbackFactory.buildFor(...)`，**.defaultToolCallbacks(toolCallbacks)** |
| MCP 客户端唯一生产侧用途 | `AiClientToolMcpNode.java:49-52` | 只 `registerBean(beanName(mcpId), McpSyncClient.class, mcpSyncClient)`，**无消费者** |
| ES 查询走哪条路 | `ElasticsearchProvider.java:33-49, 71-73` | **JDK `HttpClient` 直连 ES `_search`**；类注释："ES MCP（5007）仍保留在装配链里，但本 Provider 不再依赖它做查询" |
| Spring AI MCP 自动配置 | `application.yml` | 无任何 `spring.ai.mcp.client.*` 配置项 → 属性驱动的自动配置无输入 |

---

## 5. 结论与含义

1. **当前 main 分支上，ES MCP 的 11 个工具（含 6 个写/删）没有暴露给模型。** 三条独立证据一致：
   静态（无消费者）、运行时（9 个客户端工具清单无 MCP 工具）、配置（无 MCP 自动配置输入）。
2. **但它处于"随时可激活"的状态**：5007 库表 `status=1`、7 条绑定 `status=1`、后端启动时 MCP 进程真的被拉起并握手成功。
   只要有人把 `SyncMcpToolCallbackProvider` 重新接回 `AiClientNode`（这正是 M1 之前上游课程代码的写法），
   11 个工具会**立刻**全部到达模型。所以这是**潜在风险**，不是已发生的暴露。
3. **"在 MCP 客户端层做只读白名单过滤"这一方案的前提需要修正**：
   - 它对**当前**的 ES 查询路径**没有保护作用**——ES 查询走 `ElasticsearchProvider` 的 **JDK HttpClient 直连 `_search`**，完全不经过 MCP。
   - 它的真实价值是**第二道防线**：万一 MCP 注入路径被恢复，白名单能挡住写工具。作为回归防护是合理的。
4. **真正的强制点在 ES 服务端**：本地 ES 无安全特性（M4 文档已注明），且 Provider 直连 `_search` 可发任意 HTTP 请求体。
   仅靠 MCP 层白名单挡不住直连路径。若要真正的写保护，应在 ES 侧启用安全特性 + 只读凭据。

## 6. 未做（边界）

- 未验证"把 MCP 工具接回模型"后的实际行为（未改动任何代码）。
- 未验证 ES 服务端安全特性现状（超出本次核查范围）。
- 运行时证据取自**当前已构建的 jar**（`ai-agent-station-study-app.jar`，构建于 2026-09-25 21:51），
  与工作区源码的 MCP 相关代码一致（见 §4 静态检查）；未重新打包对比。
