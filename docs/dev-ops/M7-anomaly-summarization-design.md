# M7 设计：异常检测与现象归纳中间层（Anomaly Summarization Layer）

> 本文档是 **M7「异常检测与现象归纳中间层」的开发依据，自包含**。
> 上游依据：`M4-status-and-next-tasks.md` §4 P1-3 行"Metrics 源（Loki/Prometheus）——
> 这是解锁前端 Metrics/Trace 查看器的前置条件"、`P1-cross-source-correlation-design.md` 决策 3
> （"只做机械对齐，不做因果推断"）。
>
> 状态：**设计待评审，尚未开工**。本文只写"要解决什么 / 接口长什么样 / 边界在哪"，
> 不含实现代码；落地后请按本仓库惯例补 §6 真实验证证据。

---

## 1. 要解决的问题

### 1.1 现状（事实，非推测）

当前 Agent 拿到的"证据"是**原始行**：`QueryResult.rows` 是 ES 命中的原始文档行、
MySQL/PG 的原始记录行，经 `QueryResult.toModelJson()` 剥离 `rawRef` 后**原样进入提示词**。

由此产生三个可观测的问题：

| # | 问题 | 具体表现 |
|---|---|---|
| 1 | **Token 与信噪比倒挂** | 一次 `query_logs` 返回 200 行，其中 180 行是同一条限流 WARN。模型要读 200 行才能得出"限流告警重复出现"这一句话的结论。M4 实测里出现过 `query_logs x36` 的连续调用，本质是模型在**用多次检索代替聚合** |
| 2 | **"多少算多"无法判定** | 模型看到 1 条 `PAY_TIMEOUT`，与看到 300 条 `PAY_TIMEOUT`，在提示词里都是"若干行文本"。**没有基线、没有计数、没有占比**，模型只能含糊表述 |
| 3 | **异常判定被推给模型** | "这行日志算不算异常"是一个**数学问题**（频次、变化幅度、离群程度），但当前架构把它交给了语言模型去"感觉"。这是精度损失的主要来源，也是模型幻觉的温床（对应 §1.2） |

### 1.2 一个真实踩过的坑（来自 `data/review/PR.md`）

> "因为我只做了 log 分析，有些 input 中的时间段内没有 log，或者说有些有 log 文件，但 log 中没有包含
> 'error'，所以需要随机选择已有结果填充空结果。"

这段注释暴露的是：**"查不到" 与 "正常" 在当前架构里无法区分**。模型拿到空 `rows`，
在没有任何计数/基线信息的情况下，极易把"空"推理成"服务挂了"。

### 1.3 目标

> **把"这算不算异常、有多严重"从模型的脑内计算，变成后端给出的机械事实。**

在 Provider 返回的原始行与提示词之间，插入一层**只做数学**的归纳层：计数、占比、
同比/环比变化幅度、离群度，并输出**结构化现象摘要**（哪类异常、出现多少次、影响面多大），
同时**保留行级原文回指能力**（`rawRef`）。

**这一层不做因果推断**——沿用 M3/P1 纪律（"是否相关/是否根因"仍由模型判断）。

---

## 2. 与非目标（边界）——**这一节是本文最重要的部分**

### 2.1 本任务明确**不做**

| # | 不做 | 理由 |
|---|---|---|
| 1 | **不做因果推断** | 沿用 M3 纪律。输出的是"这个现象存在且强度为 X"这一事实，不是"原因是 X" |
| 2 | **不替代 `query_logs` / `query_orders`** | 语义工具是**增量**：模型仍可自主决定"先看摘要，再定向拉原文"。新工具不是唯一入口 |
| 3 | **不引入统计模型（IsolationForest / Drain3 / 任何训练产物）** | 本层只用**计数 + 占比 + 阈值 + 分位数**。引入需要训练/持久化/版本化的模型是独立任务，且当前无正常期标注数据 |
| 4 | **不新增数据源类型** | 本层工作在**已有** Provider 之上。Metrics TSDB（Loki/Prometheus）是独立前置任务（见 §2.2） |
| 5 | **不改 `QueryResult` 结构与 `toModelJson()` 契约** | 见 §4 决策 1 |
| 6 | **不做自动根因排序 / 不做 Top-1 推断** | 排序即因果主张，越界 |
| 7 | **不动 `ai_stage_tool_policy` 的"默认拒绝"语义** | 新能力必须显式授权（见 §5） |
| 8 | **不做前端视图改造** | 前端渲染是独立任务；本层只保证 `rawRef` 仍可回指 |

### 2.2 前置依赖（**必须先确认，否则本任务无法端到端验收**）

| 依赖 | 状态 | 影响 |
|---|---|---|
| **Metrics 数据源（Loki / Prometheus）** | ❌ **未落地**（`M4-status §4` 列为 P1-3 候选） | 无 Metrics 时，"指标类异常"只能靠**日志聚合**与**实体记录聚合**近似。§3 因此把范围切分为 **M7a（不依赖 Metrics）/ M7b（依赖 Metrics）** |
| 故障窗语义 | ❌ 未落地，见 `M8-fault-window-semantics-design.md` | 无基线时，本层只能给**绝对计数**与**窗口内占比**，不能给"对比正常期变化幅度"。M7a 接受此限制并在输出里注明 |

> **结论**：本任务**可以独立开工**，但必须按 §3 的阶段切分交付，**不要在 M7a 里假装有基线**。

---

## 3. 范围切分与验收

### M7a：无 Metrics 依赖（可立即开工）

作用于**已有**的 `log_search` 与 `entity_query` 结果。

| 归纳维度 | 算法（纯数学） | 输出 |
|---|---|---|
| **重复度** | 按"模板键"分组计数 | `count`、`share`（占窗口内总命中比） |
| **模板键抽取** | 日志：剥离数字/UUID/时间戳/长十六进制后做归一化（**不是** Drain3，是正则归一） | `pattern`（归一化后的模板文本） |
| **严重度** | 按 `level` 字段（ERROR/WARN/INFO）分组计数 | 每级 `count` |
| **时间聚集** | 按桶（复用 `CrossSourceCorrelationService` 的分桶能力）统计每桶条数 | `peakBucket`、`peakCount` |
| **实体侧分布** | 按某字段（如 `status`、`fail_reason`）分组计数与占比 | 每值 `count` + `share` |
| **离群桶** | 桶计数序列的 **中位数 + IQR**，超出 `median + k*IQR` 标为离群（k 可配，默认 3） | `outlierBuckets[]` |

### M7b：依赖 Metrics 源（**阻塞**，前置为 Metrics Provider）

在 M7a 之上增加**数值型指标**归纳：变化幅度（故障期均值 vs 正常期均值）、
稳定项过滤（幅度 < 阈值直接不输出）、极值剔除（去最大/最小各 N 个后建基线）。

> M7b 的算法细节**不在本文展开**——它强依赖 M8 的故障窗定义，且需要先确定 Metrics Provider 的
> `EntityMeta` 形状。**本文只登记它存在，避免与 M8 重复设计。**

### 验收（M7a）

| 验收点 | 判据 |
|---|---|
| 新工具可用 | 执行阶段工具面**新增**该工具；分析/监督阶段**仍只有** `describe_entity`（四步链纪律不变） |
| 行级回指不丢 | 归纳结果的每条现象**至少携带一个 `_rawRef`**（指向该组内任意一条原文），UI 可跳 Kibana |
| 模型面不泄露 | `toModelJson()` 剥离后无 `rawRef` / `_rawRef`（沿用既有回归网的正向断言） |
| 压缩比可测 | 同一查询下，新工具 `toModelJson()` 字节数 **<** `query_logs` 的 **50%**（真实数据实测，写入 §6） |
| 不回归 | `mvn -o -B test` 全绿（当前基线 **80 通过**），且**不修改**任何既有用例 |
| 空输入不许诺 | 0 命中时返回**带 `warnings` 的空结果**，禁止返回"正常"字样（见 §7 纪律 3） |

---

## 4. 关键决策

### 决策 1：产出复用 `QueryResult`，**不新增结果类型**

与 P1 决策 1 完全同构——复用既有机制，零改动：

| 既有机制 | 复用方式 |
|---|---|
| 证据注册表 | 工具名以 `query_` 开头 → 自动进证据面板（`EV-xx`） |
| `rawRef` 回指 | 结果级 = 本次归纳覆盖的原窗口；行级 `_rawRef` = 该现象组内**代表行**的原文引用 |
| 模型版 / 事件版双通道 | `QueryResult.toModelJson()` 照旧剥离（**契约不变**） |
| `warnings` | 复用为"结论依据有多强"：样本量不足、被截断、某侧不可用、**无基线可用** |

**为什么不新增 `ObservationResult`**：新增类型意味着证据注册表、`rawRef` 解析、模型版剥离、
前端渲染**各写一套**。P1 已经证明复用 `QueryResult` 足够承载"一行 = 一个时间桶"这种非表格语义，
本层同样成立（**一行 = 一个现象**）。

### 决策 2：作为**独立能力 + 独立工具**挂出，不改变 `query_logs` 行为

新增能力值 `anomaly_summary`（名称待评审），在 `DataSourceCapability` 中登记，
由 `DataSourceToolCallbackFactory` 生成工具（形如 `query_log_summary` / `query_anomalies`）。

**关键约束**：`query_logs` / `query_orders` 的行为**一行不改**。理由：

1. 语义工具是**增量**，模型保留"自己拉原文"的能力（本任务非目标 §2.1-2）；
2. 改 `query_logs` 的返回形状会**破坏 M3 证据面板的既有断言与前端渲染**（回归成本外溢）；
3. 保留两者可做**A/B 对比**（见 §6），否则无法证明本层有收益。

> ⚠️ 注意：`DataSourceCapability` 的注释明写"**写/删类能力刻意不存在于此枚举中**"。
> `anomaly_summary` 是**只读**能力（只做聚合，不写任何东西），**不破坏**该纪律。评审时请确认这一点。

### 决策 3：**"压缩"由后端保证，不由模型自觉**

模型可以选择"要不要看摘要"，但**摘要本身的形态是后端固定的**。不在 tool schema 里暴露
`k`（IQR 系数）、阈值、分组字段等调参口。

理由：一旦把调参权交给模型，它会在多轮里反复试参数（这正是 M4 观测到的 `query_logs x36` 的成因）。
**参数进库表配置**（复用 `ai_data_source*` 的配置化思路），不进 schema。

### 决策 4：**不用 Drain3**，用正则归一

Drain3 需要训练产物（`error_log-drain.pkl`）、版本管理、以及"训练集从哪来"的问题。
本仓库当前**没有**正常期标注数据，也没有模型持久化的先例（`src/models/*.pkl` 在 Python 侧，
Java 侧无对应设施）。

**M7a 用纯正则归一**：剥离时间戳、UUID、纯数字、长十六进制、IP、URL query 后做模板键。
它不完美（会把"不同错误码"归一成同一模板），但**无状态、可解释、可单测**。
若将来证明正则归一的压缩比不足，再引入 Drain3 作为**独立的、带训练产物的任务**。

### 决策 5：**样本量纪律**——小样本不许下结论

`count < minSample`（默认 3）的现象**不标为异常**，只在结果里如实列出计数。
理由：1 条日志不构成"异常现象"，把它标成异常会制造假阳性，直接损害本层可信度。

---

## 5. 接口

### 5.1 模型可见工具（名称待评审，建议 `query_log_summary`）

```json
{
  "type": "object",
  "properties": {
    "keyword":    {"type": "string",  "description": "检索关键字（匹配日志内容，必填）"},
    "startTime":  {"type": "string",  "description": "起始时间，ISO-8601，可选，默认最近1小时"},
    "endTime":    {"type": "string",  "description": "结束时间，ISO-8601，可选，默认现在"},
    "groupBy":    {"type": "string",  "description": "分组维度，可选：pattern（默认）/ level / container"},
    "bucketMinutes": {"type": "integer", "description": "时间桶粒度（分钟），默认5"},
    "limit":      {"type": "integer", "description": "最多返回多少个现象组，默认20，上限50"}
  },
  "required": ["keyword"]
}
```

**刻意不暴露**：`k`（IQR 系数）、`minSample`、阈值、dsId、索引名、字段名。

> **定案（2026-10-17，负责人确认）**：**保持不暴露** —— 本节 §5.1 的 `groupBy` / `bucketMinutes` 视为"设计草图"，
> 实现从严（固定 `pattern` 分组 + 5 分钟桶）。理由见 `GAP-five-points-development-plan.md` §2.1.0 的定案段
> （实测够用 / 可摘除性已验证 / 多轮调参风险 / 验收成本）。将来若要扩展，按本节 schema 加参数并同步补验收用例。

### 5.2 返回（`QueryResult`：**一行 = 一个现象**）

`dsId = <来源 dsId>`、`entityCode = log_summary`

| 列 | 含义 |
|---|---|
| `pattern` | 归一化模板文本（已截断，建议 ≤ 200 字符） |
| `level` | 严重度（ERROR / WARN / INFO / UNKNOWN） |
| `count` | 该现象在窗口内的命中条数 |
| `share` | 占总命中的比例（0~1，保留 3 位） |
| `firstSeen` / `lastSeen` | 首次 / 末次出现时间（`yyyy-MM-dd HH:mm:ss`，Asia/Shanghai） |
| `peakBucket` / `peakCount` | 命中最多的时间桶及其条数 |
| `outlier` | 布尔：该桶计数是否超出 `median + k*IQR` |
| `_rawRef` | 该组代表行的原文引用（**行级**，只进事件版） |

- 结果级 `rawRef`：`summary://<dsId>/<索引或表>@<start>~<end>#groupBy=<dim>`
- `warnings`：样本量不足 / 命中被截断 / 时间解析失败计数 / **"未提供正常期基线，本结果仅含窗口内绝对计数与占比"**

### 5.3 新增/改动清单（预期，实施时以 PR 为准）

| 文件 | 改动 |
|---|---|
| **新增** `domain/.../service/datasource/LogPatternNormalizer.java` | 正则归一（无状态、纯函数、可单测） |
| **新增** `domain/.../service/datasource/AnomalySummarizationService.java` | 编排：取原始行 → 归一 → 分组计数 → 分桶 → 离群判定 → 产出 `QueryResult` |
| `DataSourceCapability` | 新增只读能力 `anomaly_summary` |
| `DataSourceToolCallbackFactory` | 新能力 → 新工具（含 schema、description）；沿用阶段裁剪 |
| **新增** `docs/dev-ops/mysql/sql/upgrade-m7-1-anomaly-summary.sql` | `ai_stage_tool_policy` 新增 `anomaly_summary` 行：执行阶段 `allowed=1`，其余 `0`；幂等 |
| **新增** `app/src/test/.../test/m7/AnomalySummarizationTest.java` | 纯单测（归一 / 分组 / share 计算 / IQR 离群 / 小样本不标异常 / 模型版剥离） |
| `docs/dev-ops/UPGRADE-hot-reload-deepseek.md` | §一 部署顺序新增本 SQL 步骤（**跳过则工具不挂载**——与 P1 同一坑） |

**不改**：Provider SPI、SSE 事件契约、`QueryResult` 结构、M3 证据派生、`DataSourceQueryService`
查询语义、`AiClientNode` 装配流程、任何既有单测。

### 5.4 层间依赖（不可破）

```
DataSourceQueryService（唯一查询入口，租户注入 + tracing）
        ↑ 复用
AnomalySummarizationService（domain，只做数学）
        ↑ 调用
DataSourceToolCallbackFactory（domain，生成语义工具）
```

`domain` **不得** import `infrastructure`。本层全部落在 `domain`，只通过
`IDataSourceRegistry` / `DataSourceQueryService` 端口访问数据。

---

## 6. 验证方式（落地后必须补真实证据）

| 层次 | 方式 | 期望 |
|---|---|---|
| 单测 | `mvn -o -B test` | 全绿（81+ 通过），**既有 80 例一例不改** |
| 单测（正向） | 新用例必须含**正向断言**（"应产出 N 个现象组、ERROR 组 count=37"） | 防止"空集上白过"——这是 `EsMcpToolExposureTest` 踩过的坑（`M4-status §10.4` 踩坑记录） |
| 压缩比 | 同窗口分别调 `query_logs` 与新工具，量 `toModelJson()` 字节数 | 新工具 **< 50%** |
| 证据不丢 | 事件版 SSE 里每条现象**都有 `_rawRef`**；点击可跳 Kibana | 沿用 `web/scripts/render-check.cjs` 断言 |
| 端到端 | 真实 ES + LLM，`aiAgentId=4`，真实限流场景 | 模型**先用摘要定位**、再定向 `query_logs` 拉原文（对比 `M4-status §10.7` 实测的 `query_logs x36`） |
| 回归 | 既有前端 `smoke.ts` / `render-check.cjs` | ALL PASS，**不改断言**（新工具名以 `query_` 开头，应自动进证据面板） |

**反例实验（必须做）**：把新工具的 `allowed` 置 0，确认模型**仍能**用 `query_logs` 完成排障
→ 证明本层是**增量**而非**依赖**，可随时摘除。

---

## 7. 纪律（不可破，沿用并新增）

1. **只讲机械事实**：输出"这个现象出现 37 次、占 62%"是事实；"这是根因"不是。后者禁止出现在本层任何字段里。
2. **`rawRef` / `_rawRef` 永不进入模型提示词**：仍由 `QueryResult.toModelJson()` 剥离，**不改该契约**。
3. **空结果不许诺正常**：0 命中时必须输出 `warnings`，措辞为
   *"窗口内未检索到匹配日志"*，**禁止**输出"服务正常"/"无异常"。
   这是 §1.2 那个坑的正面对策——**"查不到" ≠ "正常"**。
4. **小样本不下结论**：`count < minSample` 不标异常。
5. **不猜参数**：归一正则、`k`、`minSample`、桶粒度默认值全部进配置，不散落在代码里。
6. **不引入训练产物**（M7a 范围内）。
7. **`domain` 不依赖 `infrastructure`**；查询只经 `DataSourceQueryService`。
8. **V1 只经 PR 合入**，禁止直接提交。

---

## 8. 已知限制（**未做**，如实登记）

1. **无正常期基线**：M7a 只有窗口内绝对计数与占比，**给不出**"对比正常期变化了多少"。
   归因于 M8 未落地。输出里必须在 `warnings` 声明这一点。
2. **正则归一 ≠ 语义聚类**：会把"超时 3000ms"与"超时 5000ms"归为一类（**这是有意的**），
   但也会把"不同的错误码"归为一类（**这是损失**）。已知且接受，见决策 4。
3. **单关键字**：日志侧仍是 ES `match(message, keyword)`，无布尔组合（`P1-cross-source §7-3` 同款限制）。
4. **IQR 离群对低基数序列不稳**：窗口内桶数 < 5 时 IQR 退化，此时**不输出** `outlier` 标记（返回 `false` 并告警）。
5. **不做跨源现象关联**：本层只看单一来源。跨源已在 P1 做"机械时间对齐"，两者**尚未合并**（合并是独立任务）。
6. **不做现象的去重/Top-N 排序以外的智能筛选**：模型仍可能被 20 个现象组淹没（只是从 200 行降级为 20 组）。
7. **M7b（数值指标）完全未设计**：依赖 Metrics Provider + M8。本文只登记。
8. **未验证压缩比**：§6 的 < 50% 是**待验证假设**，不是已测事实。落地后如实回填，若不达标需重新评审决策 3/4。

---

## 9. 参考

| 依据 | 位置 |
|---|---|
| 统一数据源 SPI 与能力枚举 | `M1-unified-datasource-design.md`、`DataSourceCapability` |
| 只做机械对齐、不做因果推断 | `P1-cross-source-correlation-design.md` 决策 3 |
| `QueryResult` 复用先例（一行 = 时间桶） | `P1-cross-source-correlation-design.md` 决策 1 |
| `rawRef` 双通道纪律 | `M3-evidence-panel-design.md`、`QueryResult.toModelJson()` |
| 阶段裁剪与"默认拒绝" | `ai_stage_tool_policy`、`DataSourceToolCallbackFactory.buildFor` |
| "空集上白过"踩坑记录 | `M4-status-and-next-tasks.md` §10.4 |
| "查不到 ≠ 正常"的原始暴露 | `data/review/PR.md`（随机填充空结果那段注释） |
| 故障窗语义（本层的前置） | `M8-fault-window-semantics-design.md` |
