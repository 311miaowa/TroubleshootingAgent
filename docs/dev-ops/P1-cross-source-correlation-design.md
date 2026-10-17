# P1 交付：跨源关联（日志时间线 ↔ 订单时间线对齐）

> 本文档是 **P1「跨源关联」的开发与交接依据，自包含**。
> 上游依据：`docs/dev-ops/M1-unified-datasource-design.md` §10"留待后续单独做"、
> `docs/dev-ops/M4-datasource-health-design.md` §8 的 P1 ① 行（工具链与数据源健康那两块已分别合入 PR #13/#14）。
>
> 状态：**已完成**（分支 `feat/p1-cross-source-correlation`，从 V1 `cf0f000` 切出；PR 待开）。

---

## 1. 要解决的问题

"下单失败是否与限流有关"这类问题，天然需要**同时**看两侧数据并把它们摆在同一根时间轴上。
在此之前，Agent 只能分别调用 `query_logs` 与 `query_orders`，然后把"哪条日志和哪笔订单落在同一分钟"
留给**模型自己在长文本里比对**——这正是它最容易算错、也最容易含糊其辞的地方
（M4 期间的实测里，模型甚至给出过"数据不足，无法判定"的结论）。

**目标**：把"时间对齐"从**模型的脑内计算**变成**后端给出的机械事实**——一次调用同时查两侧，
按时间桶返回，桶里带两侧摘要与原文回指；"是否相关"仍由模型基于它判断。

## 2. 验收与真实结果

| 验收点 | 结果 |
|---|---|
| 执行阶段可用的新工具 | ✅ `query_correlated_timeline`（能力 `cross_source`），启动日志实测：`clientId=4102 clientType=PRECISION_EXECUTOR_CLIENT 允许能力=[entity_meta, cross_source, entity_query, log_search] 工具=[query_logs, query_orders, describe_entity, query_correlated_timeline]`；分析/监督/应答阶段**仍只有** `describe_entity`（四步链纪律不变） |
| 一次调用产出对齐时间线 | ✅ 见 §6 的真实 SSE 片段：5 分钟桶 × 5 桶，`2026-03-27 10:30` 桶**同时**含限流 WARN 与 `PAY_TIMEOUT` 订单 |
| 桶级可回指两侧原文 | ✅ 该桶 `_rawRef = "group-buy-market-log-2026.03.27#oGGN… | order_no=NO20260327001"` |
| 结论用上了对齐证据 | ✅ 最终报告的"数据完整性与可信度"表里出现 **时间线对齐桶数 ✅ 5 桶完整**、**证据双验证 ✅ 机械时间对齐 + fail_reason 字段** |
| 不回归 | ✅ 单测 44 例全绿（含既有 M3/M4/P0/T1、T2）；前端 `npm run build` exit 0、`smoke.ts` ALL PASS |

## 3. 关键决策

### 决策 1：产出仍是 `QueryResult`（一行 = 一个时间桶），不新增结果类型

这样 M3 建立的既有机制**全部直接复用**，零改动：

| 既有机制 | 复用方式 |
|---|---|
| 证据注册表 | 工具名以 `query_` 开头 → 自动进证据面板（`EV-xx`），只需把能力映射成 `Cross-Source` 类型标签 |
| rawRef 回指 | 结果级 `correlate://<日志源>+<订单源>@<start>~<end>`；行级 `_rawRef` 用 ` | ` 拼两侧引用 |
| 模型版 / 事件版 JSON | `QueryResult.toModelJson()` 照旧剥离 `rawRef` 与 `_rawRef`（模型看不到技术指针，UI 能看到） |
| warnings 语义 | 复用为"结论依据有多强"的告警位：某侧不可用/0 命中/时间解析失败都写在这里 |

若改用新值对象，则证据注册表、rawRef 解析、模型版剥离、面板渲染全都要各写一套——**不划算**。

### 决策 2：按**能力**发现两侧来源，不硬编码 dsId

`CrossSourceCorrelationService` 通过 `IDataSourceRegistry` 找"声明了 `log_search` / `entity_query` 的来源"，
并在同类多源时按 dsId 排序取第一个（**选择稳定、可复现**）。将来接第三个源、或把订单源换成别的实现
（PostgreSQL / ClickHouse …），这里**一行都不用改**——这是 M1"接源不改 Agent 侧代码"承诺的又一次兑现。

### 决策 3：只做机械对齐，不做因果推断（沿用 M3 纪律）

输出的是"这个时间桶里同时有日志与订单"这一**事实**，以及两侧摘要；是否因果由模型判断。
真实运行里模型正是据此把 10:30 的同桶现象判为"时间吻合但被 `PAY_TIMEOUT` 字段证伪因果"。

### 决策 4：时间归一集中在一处（`RowTimes`）

两侧时间在代码里形态完全不同，这是本任务**最大的坑**：

| 来源 | 原始形态 | 归一 |
|---|---|---|
| ES 日志 `@timestamp` | 带偏移的字符串 `2026-03-27T10:30:00+08:00` | → `Instant` |
| MySQL 订单 `create_time` | `datetime`（无时区）→ JDBC `Timestamp` → JSON 里是 epoch 毫秒 `1774578600000` | → `Instant` |

`RowTimes.parse(Object)` 统一处理 ISO（带偏移/裸）、`yyyy-MM-dd HH:mm[:ss[.SSS]]`、epoch 秒/毫秒、
`Timestamp`/`Date`/`LocalDateTime`/`OffsetDateTime`；**无时区值一律按 Asia/Shanghai 解释**
（与订单库 `serverTimezone=Asia/Shanghai`、日志里的 `+08:00` 一致）；解析不出来**返回 null**，
由调用方跳过并计数告警——**不猜默认时间**。

## 4. 接口

**工具**（模型可见）：`query_correlated_timeline`

```json
{
  "type": "object",
  "properties": {
    "keyword":       {"type": "string",  "description": "日志侧检索关键字（匹配日志内容，必填）"},
    "startTime":     {"type": "string",  "description": "起始时间，ISO-8601，可选，默认最近1小时"},
    "endTime":       {"type": "string",  "description": "结束时间，ISO-8601，可选，默认现在"},
    "bucketMinutes": {"type": "integer", "description": "时间桶粒度（分钟），默认1；对齐时间线时建议1~5"},
    "limit":         {"type": "integer", "description": "每一侧最多取多少条，默认50，上限200"}
  },
  "required": ["keyword"]
}
```

**返回**（`QueryResult`）：`dsId=cross_source`、`entityCode=timeline`

| 列 | 含义 |
|---|---|
| `bucketStart` | 桶起点，`yyyy-MM-dd HH:mm:ss`（Asia/Shanghai） |
| `logCount` / `orderCount` | 桶内两侧条数（**单侧桶也保留**：时间线是并集，不是交集） |
| `logSummary` / `orderSummary` | 桶内两侧摘要（日志：level · application · message；订单：order_no · status · fail_reason · amount），单体截断 140 字符 |

- 结果级 `rawRef`：`correlate://<日志源>+<订单源>@<start>~<end>`
- 行级 `_rawRef`：该桶涉及的原文引用，` | ` 连接（ES 是 `索引#_id`，订单是 `order_no=...`）
- `warnings[0]` 固定为来源说明：`已对齐：日志@ds_log_es（2 条） + 订单@ds_order_mysql（4 条）；桶粒度 5 分钟；时间按 Asia/Shanghai 解释`

**keyword 为什么必填**：ES 侧的空关键字会被 Provider 拒绝（`keyword 不能为空`），
且放开就等于全量扫描。缺 keyword 时**不发起任何查询**，直接返回带说明的空结果。

## 5. 接入点（改了什么）

| 文件 | 改动 |
|---|---|
| **新增** `domain/.../service/datasource/RowTimes.java` | 时间值归一（4 种形态 + 容错），`BIZ_ZONE = Asia/Shanghai` |
| **新增** `domain/.../service/datasource/CrossSourceCorrelationService.java` | 编排：按能力发现两侧源 → 各查各的（失败如实告警）→ 归一 → 分桶 → 产出 `QueryResult` |
| `DataSourceToolCallbackFactory` | 新增 `cross_source` 能力 → 工具 `query_correlated_timeline`（含 schema、description）；仅在"至少一侧可查"时挂出 |
| **新增** `docs/dev-ops/mysql/sql/upgrade-p1-1-cross-source-correlation.sql` | `ai_stage_tool_policy` 新增 4 行 `cross_source`（执行阶段 `allowed=1`，其余 0）；幂等 |
| `web/src/utils/evidence.ts` | `EvidenceKind` 增 `cross`；能力/工具名 → `Cross-Source` 类型标签；机械解读的计量单位用"时间桶"；`humanizeArgs` 展示桶粒度 |
| `web/src/utils/rawRef.ts` | `correlate://` 结果级引用解析；桶级复合 `_rawRef` 识别为 `multi` 并给可读标签（不再被误判成单个 ES 文档） |
| `web/scripts/smoke.ts` | 新增 10 项断言（类型识别 / 解读单位 / rawRef 解析 / 兼容回归） |
| **新增** `app/src/test/.../test/p1/P1CrossSourceCorrelationTest.java` | 14 个用例（时间归一 / 分桶 / 缺失侧 / 0 命中 / 解析失败 / 模型版剥离） |
| `docs/dev-ops/UPGRADE-hot-reload-deepseek.md` | §一 部署顺序新增本 SQL 步骤（跳过则工具不挂载） |

**不改**：Provider SPI、SSE 事件契约、`QueryResult` 结构、M3 证据派生逻辑、`DataSourceQueryService` 查询语义。

## 6. 验证证据

### 6.1 单测（离线、无需外部服务）

```
mvn -o -B test → Tests run: 44, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
（P1CrossSourceCorrelationTest 14 例；其余 30 例为既有 M3/M4/P0/T1、T2 用例，全部保持绿）
```

覆盖要点：日志 ISO(+08:00) 与订单裸 datetime **同桶**（时区口径）、epoch 秒/毫秒、`Timestamp`、
5 分钟桶合并、跨桶分别列出且升序、缺 keyword 拒绝且不发查询、订单侧未接入/查询失败如实告警、
两侧 0 命中提示"窗口内没有"而非"无关"、时间解析失败计数告警、模型版无 `_rawRef` 而事件版有。

### 6.2 前端

```
npm run build（tsc -b && vite build）→ exit 0
node scripts/.smoke.mjs → ALL PASS（新增 10 项：Cross-Source 类型、时间桶计量、correlate:// 解析、
                                      桶级 multi 回指标签、既有 es:// 与单文档解析不回归）
```

### 6.3 真实端到端（真实 ES + MySQL + LLM）

请求：`aiAgentId=4`，提示词要求"用跨源时间线把日志与订单按时间对齐（窗口 2026-03-27 全天）"。
SSE 抓到的工具调用与返回（节选）：

```json
{"toolName":"query_correlated_timeline","dsId":"cross_source","entityCode":"timeline","subType":"result",
 "arguments":"{\"keyword\":\"限流\",\"startTime\":\"2026-03-27T00:00:00+08:00\",\"endTime\":\"2026-03-27T23:59:59+08:00\",\"bucketMinutes\":5,\"limit\":200}",
 "result":"{\"columns\":[\"bucketStart\",\"logCount\",\"orderCount\",\"logSummary\",\"orderSummary\"],
   \"costMs\":145,\"dsId\":\"cross_source\",\"entityCode\":\"timeline\",
   \"rawRef\":\"correlate://ds_log_es+ds_order_mysql@2026-03-26T16:00:00Z~2026-03-27T15:59:59Z\",
   \"rows\":[
     {\"bucketStart\":\"2026-03-27 10:30:00\",\"logCount\":1,\"orderCount\":1,
      \"logSummary\":\"WARN · group-buy-market · 用户访问拼团项目被限流 - 用户ID: user_test_001, 产品：product_001, 原因：访问频率过高\",
      \"orderSummary\":\"NO20260327001 · FAILED · PAY_TIMEOUT · 199.00\",
      \"_rawRef\":\"group-buy-market-log-2026.03.27#oGGNL50BuQgHrtIGkLeN | order_no=NO20260327001\"},
     {\"bucketStart\":\"2026-03-27 14:30:00\",\"logCount\":0,\"orderCount\":1,\"orderSummary\":\"NO20260327002 · SUCCESS · 299.00\"},
     {\"bucketStart\":\"2026-03-27 14:35:00\",\"logCount\":1,\"orderCount\":0,\"logSummary\":\"ERROR · csdn-auto-publisher · 文章发布失败 - 触发 API 限流\"},
     {\"bucketStart\":\"2026-03-27 15:00:00\",\"logCount\":0,\"orderCount\":1,\"orderSummary\":\"NO20260327003 · FAILED · INSUFFICIENT_BALANCE · 399.00\"},
     {\"bucketStart\":\"2026-03-27 16:20:00\",\"logCount\":0,\"orderCount\":1,\"orderSummary\":\"NO20260327004 · SUCCESS · 59.90\"}],
   \"total\":5,\"truncated\":false,
   \"warnings\":[\"已对齐：日志@ds_log_es（2 条） + 订单@ds_order_mysql（4 条）；桶粒度 5 分钟；时间按 Asia/Shanghai 解释\"]}"}
```

**10:30 那个桶就是产品要的答案**：限流告警与下单失败落在同一分钟，且桶级引用同时指向两侧原文。
质量监督阶段的评估也确认了这条链路：*"跨源检索：调用 query_logs / query_orders / query_correlated_timeline
三类查询"*、*"时间线 5 桶与两源明细逐条对应，无遗漏、无冲突"*、*"关键交叉验证：10:30 同桶现象被
PAY_TIMEOUT 字段证伪因果，逻辑严密"*。

原始证据：`data/m4-evidence/p1-sse.txt`（`/data/` 已 gitignore）。

## 7. 边界与已知限制（**未做**，如实登记）

1. **不做因果推断**：只给"时间上吻合/不吻合"的机械事实，判定留给模型（决策 3）。
2. **订单侧没有 status 过滤**：`EntityQuery` 只有 `orderNo` + 时间窗，本任务**不改 Provider SPI**，
   因此订单侧返回窗口内**全部**订单（实测 4 条含 2 条 SUCCESS）；按状态筛选由模型自行完成。
   若要后端过滤，需给 SPI 加条件或扩展 `query_template`（属另一个任务）。
3. **日志侧单关键字**：内部是 ES `match(message, keyword)`，没有多关键词/布尔组合；
   要更复杂的检索仍应直接用 `query_logs`（`termFilters` 已在值对象里预留但未接线）。
4. **跨源"同一主体"关联受脱敏限制**：订单 `user_id` 是敏感字段（`us****01`），
   日志里的用户是明文 `user_test_001`，两者**无法直接比对**。真实运行的监督评估就点了这一条
   （建议"补一次按 user_id 的定向关联"）。要真做需要脱敏策略配合（如可比对的哈希），属独立议题。
5. **只取每侧第一个同类源**：多源时按 dsId 排序取第一个（确定但简单）；多源联合对齐未做。
6. **前端专属"双轴时间线"视图** —— ✅ 已由 **P1-3** 补齐（`web/src/components/evidence/TimelineView.tsx`，
   见 `docs/dev-ops/P1-3-timeline-view-design.md`）：泳道 = 数据侧、横轴 = 时间、同一时间桶一条贯穿竖线，
   两侧都有数据的桶高亮；点击标记展开该桶两侧摘要与**可逐条复制/跳 Kibana** 的原文引用。
7. **桶上限**：每侧 ≤200 条 → 桶数 ≤400；极端窗口（如 1 分钟桶 × 全天）会退化为"很多桶"，
   实际使用建议配合窗口与桶粒度。

## 8. 后续可做（Phase B 候选）

> 进展：**"订单侧条件过滤"已由 P1-2 落地**（`EntityQuery.filters` + `${filters}` 占位符 + 字段字典驱动 schema，
> 见 `docs/dev-ops/P1-2-postgres-third-source-design.md`）；**"按 key 关联"的关联键（`order_no`，未脱敏）也已就位**，
> 但真正的跨源 JOIN 仍未做。下表保留原始候选并标注状态。

| 候选 | 状态 | 价值 |
|---|---|---|
| 前端专属时间线视图（双轴/泳道，桶内逐条可点回指） | ⏳ 未做 | 把"对齐"从表格升级为一眼可见的时间轴 |
| 订单侧条件过滤（status / 金额区间） | ✅ 机制已落地（P1-2） | 订单实体的模板还没加 `${filters}`，要开只需改数据 + 一次回归 |
| 按用户/会话的键关联（需脱敏策略配合） | ⏳ 未做 | 回答"是不是同一个用户"这一追问 |
| 按 `order_no` 的跨源键关联（订单 ⇄ 支付流水） | ⏳ 未做（键已就位） | 比时间桶更硬的证据 |
| 多源联合对齐（>2 源） | 🔶 部分（P1-2 支持 `entityCode` 选一侧） | 第三源落地后自然需要 |
| 关联结论沉淀为证据块摘要 | ⏳ 未做 | Diagnosis 还没有专门的"关联"段落 |
