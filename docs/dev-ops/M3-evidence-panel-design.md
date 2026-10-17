# M3 详细设计：证据面板 + Tool Call 事件结构化

> 本文档是 **M3 的开发依据**。新会话请先读本文档，再读 `docs/dev-ops/M1-unified-datasource-design.md` 的「0. 交接信息」（环境事实与工具链坑都在那里）。
>
> 状态：**设计已补全，两个关键决策已拍板**（§2）。落地顺序见 §6，按 M1 的节奏「先补全设计 → 再分步实现，每步独立可验证」。

---

## 0. 交接信息（现状与起点）

### 当前状态

- M1（M1.0 ~ M1.4）已**全部完成并合入** `V1-for-secondary-development`（最新 `83ba6cd`，PR #1 ~ #5）。
- **M3.0（rawRef 落地）已实现并端到端验证通过**：`ElasticsearchProvider` 直连 ES `_search`（拿 `_id`/`_index`）、
  `MysqlOrderProvider` 补 `order_no`、`QueryResult` 注入行级 `_rawRef` + 结果级 `rawRef`。
  验证：`M3RawRefProviderTest`（JUnit4，不依赖 Spring 上下文）经 `JUnitCore` 直跑，2 个用例全绿
  （ES 行级 `_rawRef=索引#_id`、MySQL 行级 `_rawRef=order_no=xxx`、`columns` 均不含 `_rawRef`）。
- **M3.1（Tool Call 事件结构化）已实现并单元验证通过**：新增 `ToolCallEventEntity` / `ToolCallEventSink`；
  `DataSourceToolCallbackFactory` 重写双参 `call(toolInput, toolContext)`（经 Spring AI 原生 `ToolContext` 通道发
  `start`/`result` 事件）；`AbstractExecuteSupport` 提供 `toolCallContext(...)`；Step1~4 的 `.prompt()` 链统一加
  `.toolContext(...)`。验证：`M3ToolCallEventTest` 经 `JUnitCore` 直跑，2 用例全绿（双参 call 发出 start+result、
  单参 call 无 sink 静默）。
- **M3.2（事件与 rawRef 打通 + order_no 过滤 + 反例验证）已实现并验证通过**：
  `QueryResult.toModelJson()` 剥离 rawRef/`_rawRef`（模型版），事件版保留完整 JSON；`EntityQuery` 加 `orderNo`、
  `MysqlOrderProvider` 渲染 `${orderNo}` 占位符（PreparedStatement 绑定）+ `upgrade-m3-2-order-filter.sql`。
  验证：`M3RawRefStripTest`（模型版无 rawRef、事件版有）+ `M3OrderFilterTest`（精确命中/不过滤/不存在）经
  `JUnitCore` 直跑；全量回归 4 个测试类 10 用例全绿（`M3RawRefProviderTest`/`M3ToolCallEventTest`/
  `M3RawRefStripTest`/`M3OrderFilterTest`）。
- **M3 后端（M3.0~M3.2）端到端验证通过**：启动应用（`aiAgentId=4` 排查请求，真实 DeepSeek + ES + MySQL），
  SSE 抓取确认 `tool_call` 的 `start`/`result` 事件成对流出、事件版含 `rawRef`+行级 `_rawRef`（`索引#_id`）、
  模型总结不含 rawRef、四步链完整（analysis→execution→supervision OPTIMIZE 回流→summary）、命中真实 ES 日志 2 条。
- 集成分支只通过 PR 合入，任何情况下不要直接在上面提交。
- M1 设计文档：`docs/dev-ops/M1-unified-datasource-design.md`（§0 有环境事实/工具链坑，务必先读）。

### 环境事实（沿用 M1，详见 M1 文档 §0）

| 事实 | 说明 |
|---|---|
| 编译 | JDK 21（`$env:JAVA_HOME='D:\develop\IntelliJ IDEA 2025.3.2\jbr'`），`mvn -o -B -DskipTests test-compile` |
| 跑测试 | `mvn test` 跑不了（app surefire 2.6 + 离线缺 vintage 引擎）；从 IDE 跑，或用 JUnit 4 `JUnitCore` 直跑 |
| ES MCP | 沙箱下 `npx` 下载会 EPERM；DB 里 5007 的 `transport_config` 已配 `npm_config_cache` 指向 workspace `.npm-cache-m1/` |
| MySQL 只读源 | 账号 `ai_ro`（口令走环境变量 `DS_ORDER_MYSQL_PASSWORD`；仓库里不放口令，自行设置） |

### M3 新踩到的事实（本次摸底，决策依据，务必先看）

1. **Spring AI 1.0.0 的 `ToolCallback` 有双参重载**：`String call(String toolInput, ToolContext toolContext)`。
   由 `DefaultToolCallingManager.executeToolCall()` 调用；`ToolContext.getContext()` 里的 map 来自
   `ToolCallingChatOptions.getToolContext()`（即 `ChatClient` 请求级 `.toolContext(Map)` 注入的内容）+
   一个 `TOOL_CALL_HISTORY` 键。→ **这就是「把 emitter/会话上下文传进工具回调」的官方通道**，不用 hack。
2. **Spring AI 1.0.0 另有 Micrometer Observation 工具观察点**：`org.springframework.ai.tool.observation.ToolCallingObservationContext`
   携带 `toolDefinition.name` / `toolCallArguments` / `toolCallResult`。但它是**应用级全局**观察（ObservationRegistry），
   本身不带 sessionId/emitter，要关联到「这一次 SSE 请求」还得再套 ThreadLocal 或 toolContext，多一层。
3. **离线 Maven 仓库里没有任何 ES 客户端**：`co.elastic.*`、`org.elasticsearch.*`、`spring-data-elasticsearch` 全缺。
   → stub 决策 1 里「改用 ES 官方 Java client 直连」**离线编译不可行**；但 `spring-web`（RestTemplate/WebClient）已在 classpath，
   ES 的 `_search` 就是 HTTP+JSON，**可以不经官方 client 直连**。
4. **ES MCP 的 `search.js` 手里其实有 `_id`/`_index`**：`search.js` 拿到 `result.hits.hits`（每个 `hit._id`、`hit._index` 都在），
   只是文本格式化时**只输出 `_source` 字段 + highlight，把 `_id`/`_index` 丢了**。要拿回 `_id` 只能「换/扩展 MCP」或「直连 ES」。
5. **`ds_log_es.conn_config` 目前是 NULL**（走 `mcp_id=5007`）；`ds_order_mysql.conn_config` 已有 JDBC JSON。→ 直连 ES 需补 conn_config。
6. **执行链是单线程顺序执行**：`AiAgentController` 用 `threadPoolExecutor` 异步起一条链，链内 `StrategyHandler` 顺序走、`.call()` 阻塞。
   → 工具回调里顺序 `emitter.send()` 是安全的，不需要并发保护。

### M1 已交付、M3 直接相关的东西

- `QueryResult.rawRef` 字段**已存在**（M1.0 起），但见 §2 决策 1。
- 语义工具 `query_logs` / `query_orders` / `describe_entity`（`DataSourceToolCallbackFactory`）。
- 数据源门面 `DataSourceQueryService`、两个 Provider（`ElasticsearchProvider` / `MysqlOrderProvider`）。

---

## 1. 目标与验收标准

**目标**：把「模型调用语义工具」这件事结构化、可视化——后端把每次工具调用的「调了哪个工具、入参、返回了什么」以结构化事件通过 SSE 实时流出；前端证据面板据此渲染工具调用过程，并用每条结果的 `rawRef` 回指原文。

| # | 验收标准 | 怎么测 |
|---|---|---|
| 1 | **`rawRef` 真正落地**：`query_logs` 每条结果可回指 ES 原文（`_index#_id`）；`query_orders` 每条结果可回指订单行（`order_no`） | 直跑 `ElasticsearchProvider.search` / `MysqlOrderProvider.query`（JUnit 4 `JUnitCore`），断言 `QueryResult.rawRef` 非空、且每行带 `_rawRef`、格式正确 |
| 2 | **Tool Call 事件实时流出**：模型每调一次语义工具，SSE 里先出现 `tool_call(start)`、后出现 `tool_call(result)`，且顺序在该 step 的 `execution_result` 之前 | 发一次 `aiAgentId=4` 排查请求，抓 SSE 文本，断言事件顺序与 `toolName`/`arguments`/`result` 内容 |
| 3 | **事件带 rawRef、但 rawRef 不进 Prompt**：`tool_call(result)` 里含 `rawRef`（结果级）与行级 `_rawRef`；模型 Prompt / `executionHistory` / 最终总结里**不出现** `rawRef` | 抓 SSE 断言 result 事件含 rawRef；日志/断点确认 `executionHistory` 与 step prompt 文本不含 rawRef |
| 4 | **M1 不回归**：四步链仍完整（阶段1→2→3→回流→4）；写/删类工具仍不可见；ES/MySQL 仍命中真实数据 | 复用 M1 回归：日志出现 `阶段2`、`阶段3`；注入工具名列表不含写/删工具 |
| 5 | **证据面板 UI（独立分支）**：消费 `tool_call` 事件，渲染工具调用过程 + 每条结果的 `rawRef` 回指动作 | UI 分支自验收（`§6 M3.3`，不占后端验收） |

---

## 2. 关键决策（**已拍板**）

### 决策 1：`rawRef` 怎么拿到 → **A（直连 ES 的 `_search`），落地用「轻量 HTTP」而非「官方 Java client」**

**结论**：`ElasticsearchProvider.search()` 从「经 MCP `callTool("search")`」改为「直连 ES 的 `_search` HTTP API」，拿到 `hits.hits[]._index` + `_id`，逐条回指原文。MySQL 侧顺手补 `order_no`。

**为什么是 A**（逐条回指原文最可靠）：
- 证据面板要「逐条回指原文」，`_id` 是最稳锚点；而 ES MCP 的 `search` 文本输出**不含 `_id`**（事实 4），只有直连 ES 或换 MCP 才能拿到。
- 直连后 `search()` 拿到的是**结构化 JSON**（`hits.hits[]`），可以**删掉现在那段脆弱的平铺文本解析**（`ElasticsearchProvider.parseHit`），是一次净简化。

**为什么「官方 Java client」不可行**：离线仓库无 `co.elastic.*`/`org.elasticsearch.*`/`spring-data-elasticsearch`（事实 3），`mvn -o` 编译不了。
**但直连不必依赖官方 client**——ES `_search` 就是 HTTP+JSON，用已在 classpath 的 `spring-web`（`RestTemplate`/`WebClient`）发一个
`POST /{index}/_search` 即可，同样拿到 `_index`+`_id`。**决策仍是 A，只是传输层选轻量 HTTP。**

**为什么不是 B（换/扩展 ES MCP）**：`search.js` 里 `hit._id` 就在手上，改一行就能输出——但它是 `npx -y` 拉起的第三方包，要改就得
fork/vendor 整个 Node 包 + 它的一串运行时依赖（`@elastic/elasticsearch` 等，且 npx 缓存在 `.npm-cache-m1` 里、已 gitignore，不可复现）。
离线环境下 vendor 成本高、可维护性差。**不选。**

**为什么不是 C（组合引用）**：`_index + @timestamp + 查询条件` 拿不到逐文档 `_id`，「逐条回指原文」被削弱，不满足验收标准 1。**不选。**

**落地要点**：
1. `ds_log_es.conn_config` 补连接 JSON（`{"host":"http://127.0.0.1:9200","auth":"..."}`，本地无认证则 `auth` 省略）。
   新增 SQL：`docs/dev-ops/mysql/sql/upgrade-m3-0-es-direct-conn.sql`（给 `ds_log_es` 写 conn_config，幂等）。
2. `ElasticsearchProvider.search()` 改为：读 conn_config → `RestTemplate`/`WebClient` POST `/{physical_name}/_search`，body 用 `query_template` 渲染（渲染逻辑保留）→ 解析 `hits.hits[]._source` + `_index` + `_id`。
3. 字段裁剪/脱敏/`max_rows` 截断顺序保持不变（见 §4 的「注入顺序」）。
4. ES MCP（5007）**保留但不参与** ES 查询路径：不再有代码依赖它，装配侧（`AiClientToolMcpNode`/`BeanRegistry`）不动，避免破坏热更新链路。

### 决策 2：Tool Call 事件怎么流出 → **A（在 `ToolCallback.call()` 里发事件），用 Spring AI 原生 `ToolContext` 通道注入 emitter**

**结论**：`DataSourceToolCallbackFactory` 的 `ToolCallback` 重写双参 `call(String toolInput, ToolContext toolContext)`，从 `toolContext.getContext()` 取「事件发送器」，执行前后各发一条结构化 SSE 事件。发送器在 4 个 step 节点的 `.call()` 链上通过 `.toolContext(Map)` 注入。

**为什么是 A**（最直接 + 实时逐条）：
- Spring AI 1.0.0 **原生支持**把请求级上下文传进工具回调：`ChatClientRequestSpec.toolContext(Map)` → `OpenAiChatOptions.toolContext`
  → `DefaultToolCallingManager.buildToolContext()` → `ToolCallback.call(toolInput, toolContext)`（事实 1）。**「把 emitter 传进工具上下文」这件事框架已经铺好，不需要 hack。**
- 事件在工具执行**当场**发出，天然「实时逐条」，且能拿到「调了哪个工具 + 入参 + 返回」。

**为什么不是 B（Observation）**：`ToolCallingObservationContext` 是**全局** Micrometer Observation（事实 2），本身不带 sessionId/emitter，
要把它关联到「这一次请求的 SSE」还得再套 ThreadLocal 或借 toolContext，等于绕回 A 的注入还要多一层全局观察器。**不选。**

**为什么不是 C（step 结束后一次性下发）**：丢失「实时逐条」体验，且 `DynamicContext` 在 `ChatClient.call()` 内部不可见，
要把结果回填进 context 反而更绕。**不选。**

**落地要点**：
1. 新增领域模型 `ToolCallEventEntity` 与事件发送抽象 `ToolCallEventSink`（见 §4）。
2. `DataSourceToolCallbackFactory.tool()` 重写 `call(String, ToolContext)`：取 sink → 发 `start` → 执行 → 发 `result` → 返回结果字符串；
   单参 `call(String)` 保留（兼容现有单测 `DataSourceToolCallbackFactoryTest`，无 sink 时不发事件）。
3. 4 个 step 节点（`Step1~Step4`）的 `.prompt(...)` 链统一加 `.toolContext(Map.of(ToolCallEventSink.KEY, sink))`；
   `sink` 由 `AbstractExecuteSupport` 提供（从 `dynamicContext` 取 emitter 发 SSE）。
4. 事件走与现有 SSE 相同的帧格式（`data: {...}\n\n`），前端无需改解析器，只多识别 `type=tool_call`。

---

## 3. 范围

设计文档 §10 定义：
> Tool Call 事件结构化（SSE）与 UI → M3 / 独立 UI 分支

拆成两块：

1. **后端：Tool Call 事件结构化** —— 模型每次调用语义工具（`query_logs` 等）时，把「调了哪个工具、入参、返回了什么」以结构化事件通过 SSE 流出（M3.0~M3.2）。
2. **前端：证据面板 UI** —— 展示工具调用过程 + 每条结果的 `rawRef` 原文回指（M3.3，独立 UI 分支）。

**M3 内还要顺手补的 M1 遗留**（与决策 1 强相关，一并做）：
- `MysqlOrderProvider` 补 `rawRef`（`order_no`）；`ElasticsearchProvider` 补 `rawRef`（`_index#_id`）。
- 订单结构化过滤（`order_no` 过滤参数）接线——M1.3 的 `query_template` 里已有 `order_no = ?` 占位符，但 `DataSourceToolCallbackFactory.parseEntityQuery` 还没把它喂进去；证据面板「回指订单」需要它能按 `order_no` 查，故 M3 顺手接上。

---

## 4. 事件与数据模型

### 4.1 Tool Call 事件（后端 → SSE → 前端）

新增领域模型（domain，`cn.bugstack.ai.domain.agent.model.entity` 或 `model.valobj.datasource`）：

```java
public class ToolCallEventEntity {
    String type;        // 固定 "tool_call"
    String subType;     // "start" | "result"
    String toolName;    // query_logs / query_orders / describe_entity
    String dsId;        // 数据源（describe_entity 为 null）
    String entityCode;  // 实体（logs / orders）
    Integer step;       // 当前 step（来自 toolContext）
    String sessionId;   // 会话（来自 toolContext）
    String arguments;   // 模型传入的 toolInput 原样（JSON 字符串）
    String result;      // 仅 subType=result：QueryResult 的 JSON（含 rawRef 与行级 _rawRef）
    Long timestamp;
}
```

事件发送抽象（domain，避免工具工厂直接依赖 servlet emitter，便于单测）：

```java
public final class ToolCallEventSink {
    public static final String TOOL_CONTEXT_KEY = "_m3_tool_call_event_sink";
    private final String sessionId;
    private final int step;
    private final Consumer<ToolCallEventEntity> emitter; // AbstractExecuteSupport 提供：序列化 + emitter.send
    public void start(String toolName, String dsId, String entityCode, String args) {...}
    public void result(String toolName, String dsId, String entityCode, String args, String resultJson) {...}
}
```

SSE 帧（与现有 `AutoAgentExecuteResultEntity` 完全同构的 `data:` 帧）：

```
data: {"type":"tool_call","subType":"start","toolName":"query_logs","dsId":"ds_log_es","entityCode":"logs","step":1,"sessionId":"...","arguments":"{\"keyword\":\"...\",...}","timestamp":...}

data: {"type":"tool_call","subType":"result","toolName":"query_logs","dsId":"ds_log_es","entityCode":"logs","step":1,"sessionId":"...","arguments":"...","result":"{\"rawRef\":\"...\",\"rows\":[{\"...\":\"...\",\"_rawRef\":\"index#id\"}]}","timestamp":...}
```

### 4.2 `rawRef` 承载形式（M3 细化，逐条回指的关键）

- **结果级 `QueryResult.rawRef`（沿用 M1 字段，String）**：描述「这批结果从哪来」的块级引用，用于证据块头部。
  - ES：`es://ds_log_es/logs/{physical_name}@{start}~{end}`
  - MySQL：`mysql://ds_order_mysql/orders/ai_order@{start}~{end}`
- **行级保留键 `_rawRef`（M3 新增）**：注入到 `QueryResult.rows` 的每一行 Map，用于逐条回指：
  - ES：`{index}#{id}`（如 `group-buy-market-log-2026.03.27#R8f2...`）
  - MySQL：`order_no={order_no}`（如 `order_no=NO20260327001`）
  - `_rawRef` 是**元数据键**，不进入 `QueryResult.columns`（`deriveColumns` 显式排除），不进 Prompt。

**Provider 注入顺序（两条纪律，写进实现）**：
1. 先按字段字典裁剪 + 脱敏 + `max_rows` 截断，**最后**再注入 `_rawRef`（否则会被 `applyFieldPolicy` 裁掉）。
2. `rawRef` 只存在于 `QueryResult` 与 SSE 事件里，**绝不进入工具返回值供模型消费的文本**——工具 `call()` 返回给模型的是
   `JSON.toJSONString(QueryResult)`，其中 `rawRef`/`_rawRef` 由事件层消费，模型侧拿到的是同一份 JSON；
   需要在「事件层 vs 模型层」做一次字段剥离：给模型的返回值去掉 `rawRef`/`_rawRef`（新增 `QueryResult.toModelJson()` 或工厂里过滤）。

---

## 5. 接入点：要改哪些现有文件

| 文件 | 改动 |
|---|---|
| `ElasticsearchProvider`（infrastructure） | `search()` 改直连 HTTP `_search`（读 conn_config）；删 `parseHit` 平铺文本解析，改解析 `hits.hits[]` JSON；设 `rawRef` + 行级 `_rawRef`；构造函数去掉 `BeanRegistry` |
| `MysqlOrderProvider`（infrastructure） | 补 `rawRef`（`order_no`）+ 行级 `_rawRef`；确认 `order_no` 过滤参数接线（配合工厂） |
| `DataSourceProviderRegistrar`（infrastructure） | ES 分支去掉 `beanRegistry` 传递（连带 `ElasticsearchProvider` 构造签名变化） |
| `DataSourceQueryService`（domain） | 不变（门面只是转发）；如做「事件 vs 模型字段剥离」，在此加一层最合适 |
| `DataSourceToolCallbackFactory`（domain） | `tool()` 重写双参 `call(String, ToolContext)`；从 toolContext 取 `ToolCallEventSink` 发 `start`/`result`；`parseEntityQuery` 接线 `order_no` 过滤 |
| 新增 `ToolCallEventEntity` + `ToolCallEventSink`（domain） | 事件模型与发送抽象（§4） |
| `AbstractExecuteSupport`（domain） | 新增 `toolCallEventSink(dynamicContext)` 工厂方法（从 emitter 发 SSE，复用 `sendSseResult` 的帧格式） |
| `Step1~Step4`（domain） | `.prompt(...)` 链统一加 `.toolContext(Map.of(ToolCallEventSink.TOOL_CONTEXT_KEY, sink))` |
| `QueryResult`（domain） | 补 `toModelJson()`（剥离 rawRef/_rawRef）或等价处理；`deriveColumns` 排除 `_rawRef` |
| 新增 SQL `docs/dev-ops/mysql/sql/upgrade-m3-0-es-direct-conn.sql` | 给 `ds_log_es` 写 `conn_config`（host/可选 auth），幂等 |
| 文档 `docs/dev-ops/UPGRADE-hot-reload-deepseek.md` 或新增 M3 升级说明 | 记录 conn_config 变更与 ES MCP 不再参与查询路径的说明 |

> 不改：`AiClientNode`/`AiClientModelNode`/`AiClientToolMcpNode`（M1 已定型）、`AiClientConfigRefreshScheduler`（M1 已把 4 张表加进 UNION）。
> ES MCP（5007）配置不动，只是代码不再依赖它做 ES 查询。

---

## 6. 落地顺序（每步独立可验证）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M3.0** ✅ | `rawRef` 落地：`ElasticsearchProvider` 直连 `_search`（HTTP）+ 拿 `_id`/`_index`；`MysqlOrderProvider` 补 `order_no`；`QueryResult` 注入行级 `_rawRef` + 结果级 `rawRef`；新增 conn_config SQL | 直跑两个 Provider（`JUnitCore`），断言 rawRef/`_rawRef` 非空且格式正确；编译通过、四步链不回归 |
| **M3.1** ✅ | Tool Call 事件结构化：新增 `ToolCallEventEntity`/`ToolCallEventSink`；工厂重写双参 `call`；`AbstractExecuteSupport` 提供 sink；4 个 step 节点加 `.toolContext(...)` | 发 `aiAgentId=4` 请求，抓 SSE 断言出现 `tool_call(start/result)`，顺序在 `execution_result` 之前 |
| **M3.2** ✅ | 事件与 rawRef 打通 + 纪律落地：`tool_call(result)` 带 rawRef/`_rawRef`；给模型的返回值剥离 rawRef；`order_no` 过滤接线；**反例验证** | 反例 1：去掉 `.toolContext`，断言事件消失；反例 2：确认 step prompt/executionHistory 不含 rawRef；`order_no` 过滤能查到指定订单 |
| **M3.3** | 证据面板 UI（**独立 UI 分支/仓库**）：消费 `tool_call` 事件，渲染工具调用 + rawRef 回指 | UI 分支自验收（后端不占） |
| **M3.4** ✅ | 单测 + 文档 + PR（合入 `V1-for-secondary-development`） | 全量回归：M1 四条验收 + M3 前四条验收（已过）；PR 待开 |

> M3.0 与 M3.1 相对独立（M3.0 只动 Provider，M3.1 只动工具回调 + step 链），可并行推进；M3.2 依赖前两者。
> M3.3 是独立 UI 分支，与后端通过「SSE 事件契约」（§4.1）解耦，可并行启动，但合入时机在后端契约冻结（M3.2）之后。

---

## 7. 已知边界（不在 M3 范围）

- 多租户（M5）、会话级版本快照（M6）——不是 M3。
- 跨源关联（日志时间线 ↔ 订单时间线对齐）——M1 §10 已列为「M1.3 之后单独做」，不是 M3。
- `rawRef` 的「实际回指动作」（跳到 Kibana/MySQL 管理台）属 UI 分支，后端只产出引用指针，不做跳转。
- 字段字典驱动的工具 schema 生成——M1 遗留，M3 只顺手接 `order_no` 过滤，不重做 schema 生成。

---

## 附：M1 文档里 M3 相关的原文引用

- §1 验收标准 4：`结果模型带 rawRef` → 「为 M3 证据面板预留，晚加要回头改所有 Provider」
- §10 明确不做：`Tool Call 事件结构化（SSE）与 UI → M3 / 独立 UI 分支`
- §0 M1.1 坑 6：`ES MCP 的 search 返回平铺文本…不返回 _id。因此 rawRef 拿不到 _id（M3 证据面板若要原文回指，需直连 ES 或换一个返回结构化结果的 MCP/接入方式）。`
