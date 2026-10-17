# 升级说明：配置热更新修复 + 对话/向量化解耦（DeepSeek）

**影响范围**：Agent 组件装配与热更新、对话模型接入方式、RAG 开关、本地 ES MCP 启用方式
**对应合并**：PR #1（`feat/m0-hot-reload-and-deepseek` → `V1-for-secondary-development`）

---

## 一、部署顺序（必须按序执行）

| 步骤 | 动作 | 跳过的后果 |
|---|---|---|
| 1 | 执行 `docs/dev-ops/mysql/sql/upgrade-m0-1-flow-config-update-time.sql` | 应用仍能启动，但**改 Prompt 不会触发热更新**（每 60 秒只打一条 WARN） |
| 2 | 执行 `docs/dev-ops/mysql/sql/switch-to-deepseek.sql`，把 `sk-你的deepseek-api-key` 换成真实 key | 对话不可用 |
| 3 | 可选：执行 `docs/dev-ops/mysql/sql/enable-es-mcp-local.sql`（按自己机器改 `npx` 路径） | Agent 能对话但**没有任何工具**，"ES 定位根因"这条主线不可用 |
| 4 | 执行 `docs/dev-ops/mysql/sql/upgrade-p1-1-cross-source-correlation.sql`（P1 跨源关联） | 执行阶段不会挂载 `query_correlated_timeline` 工具，**"把日志与订单按时间对齐"这条能力缺失**（其余查询照常） |
| 5 | （可选，P1-2 第三个数据源）先执行 `docs/dev-ops/postgres/sql/upgrade-p1-2-payment-transaction.sql`，再执行 `docs/dev-ops/mysql/sql/upgrade-p1-2-postgres-payment-source.sql` | 缺少支付流水源：`query_payments` 工具不出现，跨源对齐也只能对齐订单（其余查询照常）。**另需注入环境变量 `PGVECTOR_PASSWORD`**，否则该源不健康（健康端点会给出 hint） |
| 6 | 执行 `docs/dev-ops/mysql/sql/upgrade-m7-1-anomaly-summary.sql`（M7a 异常归纳） | 执行阶段不会挂载 `query_log_summary` 工具，**"把几百条日志归纳成现象统计"这条能力缺失**（`query_logs` 原始检索照常，其余查询不受影响） |
| 7 | 执行 `docs/dev-ops/mysql/sql/upgrade-m8-1-fault-window.sql`（M8 故障窗对比） | 执行阶段不会挂载 `query_fault_baseline` 工具，**"故障期 vs 正常基线"对比缺失**（不传故障窗字段时行为与旧版一致） |
| 8 | （P1-2 只读账号）执行 `docs/dev-ops/postgres/sql/upgrade-p1-4-pg-readonly-role.sql`，再执行 `docs/dev-ops/mysql/sql/upgrade-p1-4-pg-readonly-switch.sql`；**必须注入环境变量 `DS_PG_RO_PASSWORD`** | PG 源连不上（`passwordEnv` 解析为空）→ 该源不健康、`query_payments` 不可用；只跑 SQL 不切配置则等于没做（服务端仍用可写账号） |
| 9 | （P1-3 ES 服务端安全）`export ELASTIC_PASSWORD=<强口令>` 后 `docker compose -f docs/dev-ops/docker-compose-elk.yml up -d`；再用超管建**限定索引的只读角色 + API Key**，把 `ApiKey …` 写进 `ai_data_source.conn_config.auth` | ①不导出变量时 compose 会直接报错退出（`ELASTIC_PASSWORD is required`）；②不建只读 key 而直接用超管，等于放弃"最小权限"；③升级前连过旧 ES 的环境需同时更新 `conn_config.auth`，否则 401 |
| 10 | （P2 列注释）执行 `docs/dev-ops/mysql/sql/upgrade-p2-1-capability-comment.sql`（**含中文注释，必须用 `mysql … < 文件` 原生重定向**，不要用 PowerShell 管道） | 只是注释过期（不会导致功能异常），但看库的人会以为只有三种能力；`HEX(COLUMN_COMMENT)` 前 6 字节应为 `E883BDE58A9B` |
| 11 | 发布应用 | — |

十个脚本均为**幂等**，可重复执行；每个脚本末尾都附了回滚语句/自检语句。
（P1-2 的 PostgreSQL 脚本本机没有 `psql` 时，可用 `mvn -o test -Dtest=P1_2PaymentTransactionSeedTest` 走 JDBC 执行。）

---

## 二、环境前提

1. **Windows 上 MCP 的启动命令不能是裸 `npx`**
   Java 的 `ProcessBuilder` 会抛 `CreateProcess error=2`，必须用 `npx.cmd` 或绝对路径（脚本已处理）。Linux / macOS 无需处理。
2. **编译需要 JDK 17 / 21**
   默认 JDK 25 下 Lombok 1.18.36 不生效，`ResponseCode` 会编译失败。
3. **pgvector 必须可用**
   `pgVectorDataSource` 配置了 `setInitializationFailTimeout(1)`，连不上会快速启动失败。
4. **不再需要任何 LLM 的 api-key 环境变量**
   应用可在**无 key** 环境下启动（已实测）。对话用的 key 存在数据库 `ai_client_api` 表里。
5. **接口暴露面（P2 收口）**：默认只允许本地前端来源（`http://localhost:5173` / `http://127.0.0.1:5173`）。
   - 生产必须 `export XFG_API_KEY=<强随机串>`，否则启动只打一条 WARN、`/api/**` 无鉴权；
   - 前端构建时同步注入同名口令：`VITE_API_KEY=<同一个串>`（不注入则前端请求会 401）；
   - 换前端域名时追加 `XFG_CORS_ORIGINS=https://your-front.example`（逗号分隔，不支持 `*`）。

---

## 三、如何验证生效

1. 启动日志出现
   `Tool Stdio MCP Initialized InitializeResult[... serverInfo=Implementation[name=mcp-server-elasticsearch ...]]`
   且**不再出现** `没有需要被初始化的 ai client tool mcp`
2. 随意修改 `ai_client_system_prompt` 任一字段，60 秒内日志出现
   `检测到配置变更 [上次：x，当前：y]，开始重新加载...`
   （**P0-T2 起**，这里的 x/y 是**配置指纹** `版本:行数:提示词版本`，见 §四.3）
3. 发起一次 `POST /api/v1/agent/auto_agent`（`aiAgentId=4`），确认结论里带出了 ES 中的真实数据（而不是模型编造）

---

## 四、已知问题

1. **工具未按阶段裁剪 → 四步执行链被短路**
   模型级绑定使四个客户端都挂载了 ES 工具，包括"分析"与"质量监督"客户端。分析阶段自己完成了检索并输出 `任务状态: COMPLETED`，导致**阶段 2（执行）/ 阶段 3（质量监督）/ 回流被整段跳过**——即"质量监督判断证据不足自动回流"在这条路径上失效。
2. **ES MCP 暴露了 6 个写/删类工具**
   `create_index` / `create_mapping` / `bulk` / `reindex` / `create_index_template` / `delete_index_template`，
   而默认 ES 未启用安全特性、没有第二道防线。目前仅靠 Prompt 约束。
3. ~~**版本号机制检测不到删除操作**~~ ✅ **已修复（P0-T2，2026-09-25）**
   原问题：变更检测只看 `MAX(update_time)` 并用 `>` 比较 → 删掉最新那行后 MAX 变小，**静默不重载**；
   删掉任意非最新行则 MAX 完全不变，同样无人知晓。
   现方案：把"版本号"升级为**配置指纹** `版本:行数:提示词版本`，并**改用等值比较**——
   行数负责"增/删"、`update_time` 负责"改"（配置表 DDL 都是 `ON UPDATE CURRENT_TIMESTAMP`，
   连软删 `status=0` 也会自动刷新），版本回退（有人删了配置）同样会被判定为变更。
   **运维约定**：不要 `DELETE` 配置行，停用请软删 `UPDATE ... SET status = 0`。
   详见 `docs/dev-ops/M4-datasource-health-design.md` §6.4。
4. **整体装配非原子**
   一次热更新按 Api→ToolMcp→Model→Advisor→Client **逐个替换**（以代码为准：`RootNode→AiClientApiNode→AiClientToolMcpNode→AiClientModelNode→AiClientAdvisorNode→AiClientNode`），进行中的会话仍可能拿到
   "新 MCP 客户端 + 旧 ChatClient"的混合组合。彻底解决需要会话级版本快照。

---

## 五、RAG（向量化）说明

RAG 默认**关闭**（`xfg.embedding.enabled=false`），原因是 DeepSeek 不提供 embeddings 接口。

- 未启用时，若库中配置了 `RagAnswer` 顾问：会打印明确原因并**跳过该顾问**，
  **不会导致装配失败**，也不会在运行时抛出费解的 404。
- 需要启用时：把 `xfg.embedding.enabled` 置为 `true`，并将 `xfg.embedding.base-url` / `api-key`
  指向任意 OpenAI 兼容的向量化服务；同时确保**向量维度与 pgvector 表结构一致**
  （`vector_store_openai` 当前为 1536 维，换其它维度的模型需同步迁移表结构）。
