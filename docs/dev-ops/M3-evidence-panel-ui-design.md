# M3.3 详细设计：证据面板 UI（前端）

> 本文档是 **M3.3 前端证据面板** 的开发依据。新会话请先读本文档，再读
> `docs/dev-ops/M3-evidence-panel-design.md`（M3 后端设计，SSE 契约出处）与
> `docs/dev-ops/M1-unified-datasource-design.md` 的「0. 交接信息」（环境事实）。
>
> 状态：**设计已定，三个决策已拍板**（§2：React + Vite + TS + Ant Design / `web/` 子目录 / 可配置回指）。落地顺序见 §7。

---

## 0. 交接信息（现状与起点）

### 当前状态

- **M3 后端（M3.0 ~ M3.2）已全部合入** `V1-for-secondary-development`（远端最新 `e120355`，PR #6/#7/#8）。
- 后端已通过 SSE 输出「Tool Call 结构化事件」与「四步链阶段事件」，前端只需消费，不改后端。
- **本仓库是纯后端**（Java Maven 六模块），前端**从零建**，无任何既有前端代码、无既有技术栈约束。

### SSE 接口事实（前端必须照此实现）

| 项 | 值 |
|---|---|
| 端点 | `POST /api/v1/agent/auto_agent`（**POST**，不是 GET）|
| 请求体 | `{"aiAgentId":"4","message":"...","sessionId":"...","maxStep":3}` |
| 响应 | `text/event-stream`，`data: {json}\n\n` 帧 |
| 关键约束 | **不能用原生 `EventSource`**（只支持 GET），必须用 `fetch` + `ReadableStream` 手动解析 |

### 事件契约（后端已定型，M3.1/M3.2 产出）

每条 SSE 帧是 `data: <JSON>\n\n`，JSON 分两类：

**① Tool Call 事件**（`type=tool_call`，证据面板的核心）：

| 字段 | 说明 |
|---|---|
| `type` | 固定 `"tool_call"` |
| `subType` | `"start"`（调了哪个工具+入参）/ `"result"`（返回了什么）|
| `toolName` | `query_logs` / `query_orders` / `describe_entity` |
| `dsId` / `entityCode` | 数据源与实体（`describe_entity` 为 null）|
| `step` | 当前执行步 |
| `sessionId` | 会话 |
| `arguments` | 模型传入的 toolInput（JSON 字符串）|
| `result` | 仅 `result` 事件：`QueryResult` 的 JSON（**含 rawRef + 行级 `_rawRef`**）|
| `timestamp` | 毫秒时间戳 |

`result` 内 `QueryResult` 结构：`{dsId, entityCode, columns[], rows[{..., "_rawRef":"..."}], total, truncated, costMs, rawRef, warnings[]}`。

**② 阶段事件**（`type=analysis/execution/supervision/summary/complete`）：

| 字段 | 说明 |
|---|---|
| `type` | `analysis` / `execution` / `supervision` / `summary` / `complete` |
| `subType` | 细分（`analysis_status`、`execution_result`、`supervision_score`、`summary_overview`…）|
| `step` | 步骤（summary 为 4，complete 为 null）|
| `content` | 文本/Markdown 内容 |
| `completed` | 是否完成 |
| `sessionId` / `timestamp` | 同上 |

### rawRef 的两种形态（前端要解析）

| 位置 | 格式 | 示例 | 回指动作 |
|---|---|---|---|
| 结果级 `rawRef` | `es://<dsId>/<entity>/<索引>@<start>~<end>` | `es://ds_log_es/logs/group-buy-market-log-*@2026-03-19T16:00:00Z~2026-04-04T16:00:00Z` | 展示查询摘要 |
| 结果级 `rawRef` | `mysql://<dsId>/<entity>/<表>@<start>~<end>` | `mysql://ds_order_mysql/orders/ai_order@...` | 展示查询摘要 |
| 行级 `_rawRef` | `<索引>#<_id>`（ES）| `group-buy-market-log-2026.03.27#omGXL50BuQgHrtIGCbdf` | 跳 Kibana / 复制 |
| 行级 `_rawRef` | `order_no=<值>`（MySQL）| `order_no=NO20260327001` | 复制 / 跳管理台 |

---

## 1. 目标与验收标准

**目标**：做一个纯前端页面，用户输入排查请求后，实时看到 Agent 的「工具调用过程」（调了哪个工具、入参、返回），并能为每条结果**回指原文**核对真实性。

| # | 验收标准 | 怎么测 |
|---|---|---|
| 1 | 输入请求 → 实时渲染四步链阶段事件（分析/执行/监督/总结），不丢帧、顺序正确 | 发一次 `aiAgentId=4` 请求，页面完整走完 analysis→execution→supervision→summary→complete |
| 2 | **工具调用过程可视化**：每次工具调用显示「工具名 + 入参 + 返回」，`start`/`result` 成对展示 | 页面能看到 `query_logs(start)` → `query_logs(result)` 的卡片，含入参和返回 |
| 3 | **rawRef 回指**：`result` 的表格每行有「回指原文」动作，ES 行可跳 Kibana（或复制索引#_id）、MySQL 行可复制 order_no | 点击回指，Kibana 打开对应文档 / 复制到正确引用 |
| 4 | **rawRef 与模型内容隔离**：`result` 表格展示 rawRef，但 Agent 的文本结论（summary/analysis content）里不含 rawRef（后端已剥离，前端无需处理） | 检查页面：表格有 rawRef 列，文本结论区无 rawRef 字符串 |
| 5 | 会话可重放/可回看：同一 sessionId 的完整事件序列可滚动回看 | 长任务执行中能向上滚动查看早期工具调用 |

---

## 2. 关键决策（**先拍板，再动手**）

### 决策 1：技术栈（✅ 已拍板：A）

| 选项 | 做法 | 取舍 |
|---|---|---|
| **A（推荐）** | React 18 + Vite + TypeScript + 一个轻量 UI 库（Ant Design 或 Tailwind） | 生态最成熟、SSE 处理例程多、组件化适合证据面板；Ant Design 自带表格/折叠/标签，开发快 |
| B | Vue 3 + Vite + TypeScript + Element Plus | 若团队偏好 Vue；能力等价 |

**倾向 A**（React + Vite + TS + Ant Design）。理由：证据面板大量用「表格 + 折叠面板 + 时间线」，Ant Design 现成组件能省一半工作量；TS 能直接把 §4 的事件契约写成类型，前后端契约可校验。

### 决策 2：仓库形态（✅ 已拍板：A）

| 选项 | 做法 | 取舍 |
|---|---|---|
| **A（推荐）** | 本仓库开 `web/` 子目录（monorepo 形态，独立 Vite 工程） | 前端依赖后端 SSE 契约，放一起便于契约同步、一次 clone 全栈；但 `.gitignore` 要处理 `node_modules`/`dist` |
| B | 独立仓库 | 前后端彻底解耦；但多一个仓库、契约要跨仓库同步 |

**倾向 A**（`web/` 子目录）。理由：这是「证据面板」专用 UI，紧跟后端契约演进，monorepo 同步成本最低；独立仓库适合未来 UI 长大后再拆。

### 决策 3：SSE 客户端（已由后端事实决定，无争议）

- **必须** `fetch` + `ReadableStream`（POST 流式读 body，按 `\n\n` 切帧，剥 `data: ` 前缀解析 JSON）。
- **不能用** `EventSource`（仅支持 GET，而端点必须 POST）。
- 建议封装成 `useAgentSSE(sessionId)` 的 React Hook，内部处理：连接 → 逐帧回调 → 错误重连（仅连接失败时重连，不重放已收帧）。

### 决策 4：rawRef 回指动作（✅ 已拍板：A）

| 选项 | 做法 | 取舍 |
|---|---|---|
| **A（推荐）** | **可配置回指**：ES 行回指 = 拼 Kibana Discover URL（`KIBANA_BASE_URL` 环境变量配置）；未配 Kibana 时退化为「复制 索引#_id」；MySQL 行 = 复制 `order_no` | 兼顾「真回指」与「无 Kibana 也能用」，本地/生产都通 |
| B | 只做复制引用，不跳转 | 最简，但「回指原文」体验打折 |

**倾向 A**。理由：本地开发环境有 Kibana（docker-compose ELK 带 Kibana），配 `KIBANA_BASE_URL=http://localhost:5601` 即可真跳转；生产无 Kibana 时自动退化为复制。

### 决策 5：整体布局（✅ 已拍板：三栏浅色）

- **浅色主题**（Ant Design 默认 light）+ 顶部 Header。
- **三栏**：左侧工具栏（宽度可拖拽调节）｜ 中间输入+结果 ｜ 右侧回指面板。
- **左上角数据源选择器**：纯展示/过滤——从 `describe_entity` 事件的 `result` 提取 `dsId/entityCode/entityName` 列表，选择后只展示该数据源的工具调用结果，不影响后端请求。

### 决策 6：左侧工具栏内容（✅ 已拍板：工具调用历史）

- 左侧列出当前会话所有 `tool_call` 事件（`工具名.subType` + 时间），点击定位/高亮到中间结果区的对应卡片。

### 决策 7：右侧回指面板触发（✅ 已拍板：选中后展示）

- 点击中间结果区的某条 `tool_call(result)`（或后续的结果表格某一行），右侧面板展示该条结果的 `rawRef` + 行级 `_rawRef`，并提供「跳 Kibana / 复制」动作。

---

## 3. 范围

**做**：
- 单页：输入请求 → 发 SSE → 实时渲染四步链 + 工具调用卡片 + rawRef 回指。
- 工具调用卡片的「入参 / 返回」格式化展示（JSON 高亮 + 结果表格）。
- 会话事件流的时间线滚动回看。

**不做**（明确边界）：
- 不接后端鉴权/多租户（M5 才做，前端只传 sessionId/aiAgentId/message）。
- 不做「Agent 结论编辑/点赞/反馈」等交互增强（后续里程碑）。
- 不做移动端适配（桌面优先，内部工具）。
- 不做事件持久化/断点续传（session 关闭即清，M6 版本快照才做持久化）。

---

## 4. 数据契约（TypeScript 类型，照 §0 事件契约直译）

```ts
// 后端 ToolCallEventEntity / AutoAgentExecuteResultEntity 的 TS 镜像
export type AgentEvent =
  | ToolCallEvent
  | StageEvent
  | CompleteEvent;

export interface ToolCallEvent {
  type: "tool_call";
  subType: "start" | "result";
  toolName: string;
  dsId: string | null;
  entityCode: string | null;
  step: number;
  sessionId: string;
  arguments: string;          // JSON 字符串
  result?: string;            // 仅 result 事件：QueryResult JSON 字符串
  timestamp: number;
}

export interface StageEvent {
  type: "analysis" | "execution" | "supervision" | "summary";
  subType: string;
  step: number | null;
  content: string;
  completed: boolean;
  sessionId: string;
  timestamp: number;
}

export interface CompleteEvent {
  type: "complete";
  content: string;
  completed: boolean;
  sessionId: string;
  timestamp: number;
}

export interface QueryResult {
  dsId: string;
  entityCode: string;
  columns: string[];
  rows: Array<Record<string, unknown>>; // 每行可能含 _rawRef
  total: number;
  truncated: boolean;
  costMs: number;
  rawRef: string | null;      // 结果级
  warnings: string[];
}
```

---

## 5. 页面与组件结构

三栏布局（浅色主题，决策 5）：

```
App（ConfigProvider light theme）
└─ Layout
   ├─ Header（左上角：标题 + DataSourceSelect；右侧：状态条）
   └─ Layout（三栏主体）
      ├─ SiderLeft（宽度可拖拽，决策 6）
      │  └─ ToolCallHistory：当前会话 tool_call 事件列表，点击定位中间卡片
      ├─ Content（中间：输入 + 结果）
      │  ├─ 输入区（aiAgentId / message / maxStep / 发送 / 停止）
      │  └─ 结果区
      │     ├─ StepTimeline（按 step 分组）
      │     │  ├─ StageBlock（analysis/execution/supervision/summary，Markdown）
      │     │  └─ ToolCallCard（start→result 成对折叠）
      │     │     ├─ 头部：toolName + dsId/entityCode + step + costMs
      │     │     ├─ 入参区：arguments JSON
      │     │     └─ 结果表格：columns → rows（含 _rawRef 列，行可选中）
      └─ SiderRight（决策 7）
         └─ RawRefPanel：展示选中结果的 rawRef + 行级 _rawRef + 跳 Kibana/复制
```

**状态管理**：单个 session 的 SSE 事件是**追加式流**，用 `useReducer` 维护一个 `AgentEvent[]` 数组即可，不需要全局 store。跨区域的交互（选中行、数据源过滤、点击历史定位）通过 `EvidencePanel` 的本地 state 提升传递。

**拖拽调宽**：左侧 Sider 宽度存 state，加一个 `onMouseDown` 拖拽分隔条更新宽度（Ant Design Sider 无内置拖拽，需手写几行）。

**Markdown 渲染**：`content`（summary/analysis）是 Markdown，用 `react-markdown`。

---

## 6. 接入点（前端文件结构）

```
web/
├─ package.json / vite.config.ts / tsconfig.json / index.html
├─ .env.example              # VITE_API_BASE=http://127.0.0.1:8099  VITE_KIBANA_BASE=http://127.0.0.1:5601
└─ src/
   ├─ main.tsx / App.tsx
   ├─ api/
   │  └─ agentSSE.ts         # fetch+ReadableStream 解析 SSE 帧 → AgentEvent 流
   ├─ hooks/
   │  └─ useAgentSSE.ts      # 连接/逐帧回调/重连/状态
   ├─ types/
   │  └─ events.ts           # §4 的 TS 类型
   ├─ components/
   │  ├─ EvidencePanel.tsx
   │  ├─ StepTimeline.tsx
   │  ├─ StageBlock.tsx
   │  ├─ ToolCallCard.tsx
   │  └─ RawRefLink.tsx      # rawRef 解析 + 回指动作（Kibana/复制）
   └─ utils/
      ├─ parseSseFrame.ts    # data: {...}\n\n → JSON
      └─ rawRef.ts           # 解析 es:// / mysql:// / 索引#id / order_no=
```

> 后端无需任何改动（SSE 契约已定型）。跨域：后端 `AiAgentController` 已有 `@CrossOrigin(origins="*")`，本地 Vite 开发服务器直连 8099 即可。

---

## 7. 落地顺序（每步独立可验证）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M3.3.0** | 脚手架 + 契约类型 + SSE 客户端：Vite 工程、`parseSseFrame`、`useAgentSSE` 连上后端、控制台打印事件 | 发一次请求，控制台按序打印 tool_call/analysis/... 事件，不丢帧 |
| **M3.3.1** | 四步链渲染：StepTimeline + StageBlock，Markdown 渲染阶段 content | 页面完整展示 分析→执行→监督→总结 四块 |
| **M3.3.2** | 工具调用卡片：ToolCallCard（入参 JSON + 结果表格），start/result 成对折叠 | 每次 query_logs/query_orders/describe_entity 出现一张卡，含入参和返回表格 |
| **M3.3.3** | rawRef 回指：RawRefLink 解析 + 回指动作（Kibana 跳转/复制），结果表格加回指列 | ES 行可跳 Kibana（或复制索引#_id），MySQL 行复制 order_no |
| **M3.3.4** | 打磨 + 文档 + PR：状态条/重连/样式收尾，README 说明启动方式 | 验收标准 1~5 全过 |

> M3.3.0~M3.3.3 严格依赖顺序（契约→渲染→工具卡→回指）。每步都是前端可独立启动的 Vite dev，直接连本机 8099 后端验证。

---

## 8. 多轮会话「前端丢记录」修复（追加式会话）

### 8.1 现象与复现

同一会话里连续问两个问题，界面只剩最后一个问题的记录：第一个问题的提问、证据、结论全部消失（刷新页面后依旧如此，因为持久化里已经被覆盖）。

### 8.2 根因（100% 在前端 Store，与后端无关）

`web/src/store/useSessions.ts` 的 `run()` 每次提问都把会话状态**当成单轮来重置**：

| 字段 | 修复前 | 后果 |
|---|---|---|
| `question` | `question`（直接覆盖） | 旧问题文本没了，历史侧栏与标题只剩最后一次 |
| `title` | `s.question ? s.title : makeTitle(question)` | 标题退化成最后一个问题 |
| `events` | `events: []`（清空） | 旧事件流整条丢弃 → 证据、时间线、结论全丢 |

即：**事件流不是追加的，而是每轮重建**。这与 §4/§0 的「追加式事件流、可回看」前提直接冲突。

### 8.3 修复

前端做三件事，后端零改动：

1. **会话改为多轮结构**（`web/src/types/session.ts`）：新增 `Turn { question, at }`，`Session.turns: Turn[]` 追加保存每一次提问；`question` / `title` 只表示"首轮"（保持历史侧栏与标题语义不变）。
2. **`run()` 只追加、不重置**：`turns: [...(s.turns ?? []), { question, at: now }]`，`events: s.events ?? []`，`title` 仅在首轮生成。
3. **迁移旧数据**（`load()`）：历史 localStorage 里只有单个 `question` 的会话，自动升级成 `turns: [{ question, at: createdAt }]`，不丢老记录。

### 8.4 多轮下的派生逻辑（易错点）

事件流变成跨轮累积后，直接跑原来的派生函数会把两轮混在一起。`web/src/utils/investigation.ts` 的处理：

| 派生 | 取值范围 | 理由 |
|---|---|---|
| 证据列表 / 时间线 `buildTimeline` | **全会话** | 追加式，历史轮必须能回看 |
| 结论 `buildConclusion` | **当前轮**（`eventsOfCurrentTurn`） | 取"最后一次完成的 summary"，不能被上一轮的更长文本抢走 |
| 诊断 `buildDiagnosis`、耗时 `runDuration` | **当前轮** | 诊断只针对本轮结论；耗时是本轮时长 |

`buildTimeline` 另做两处防串轮：

- 每个 Action 带 `turn`（由 `turnIndexOf` 按时间戳归属），`UNDERSTAND INCIDENT` 用 `turn + step` 去重、`DIAGNOSE` 按 `turn` 去重 —— 因为 `step` 每轮都从 1 重新计数，只用 `step` 会把第二轮的「理解问题 / 结论」并进第一轮（这正是另一种形态的信息丢失）。
- `turns.length > 1` 时，把每个问题本身画成时间线节点 `USER QUESTION` / `USER QUESTION #N`，作为轮次之间的天然分隔（单轮不加，避免与标题重复）。

`buildDiagnosis` 的"已确认结论"仍允许锚定到**任意轮**的证据行：结论来自本轮，但它引用的数据可以在更早一轮取过，这是诚实映射而非串轮。

### 8.5 验证

- `web/scripts/smoke.ts`（+8 检查）：`turns` 追加、`run()` 不清空 `events`、旧数据迁移、`buildConclusion` 取当前轮、两轮同名 `DIAGNOSE` 不合并、`USER QUESTION #2` 出现、历史侧栏可搜到旧轮问题。
- `web/scripts/render-check.tsx`（+3 检查，react-dom/server SSR）：多轮会话把两轮问题都渲染在时间线上，且两轮结论都进 DOM。

### 8.6 边界（本次不解决）

- 本次只保证**前端不丢信息**。模型侧的多轮上下文由后端 `PromptChatMemoryAdvisor` + `MessageWindowChatMemory(200)` 提供，通过 step 节点的 `chat_memory_conversation_id = sessionId` 绑定（**每 JVM 内存态**，重启即失，多实例不共享）——属于上下文管理课题，本次不动。
- localStorage 持久化仍是单浏览器、无版本快照、无跨 session 检索（原 §9 边界不变）；服务端会话持久化仍是 M6。

---

## 9. 已知边界（不在 M3.3 范围）

- 鉴权 / 多租户（M5）、会话持久化 / 版本快照（M6）。
- 事件历史入库、跨 session 检索。
- 模型侧多轮上下文治理（见 §8.6）。
- 移动端适配、暗色主题、国际化。
- 「回指原文」的真跳转依赖环境有 Kibana / 管理台；无则退化为复制引用。
- 前端不发任何写操作，只读消费 SSE。

---

## 附：后端契约出处

- Tool Call 事件：`ToolCallEventEntity`（`subType=start/result`，字段见 §0）。
- 阶段事件：`AutoAgentExecuteResultEntity`（`type/subType/step/content/completed/timestamp/sessionId`）。
- rawRef 剥离纪律：`QueryResult.toModelJson()`（模型版无 rawRef）——前端拿到的 `result` 是**事件版**（含 rawRef），这是有意为之。
