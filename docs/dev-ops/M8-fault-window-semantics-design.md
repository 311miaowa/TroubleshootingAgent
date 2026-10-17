# M8 设计：故障窗语义与对称正常基线（Fault Window & Symmetric Baseline）

> 本文档是 **M8「故障窗语义与对称正常基线」的开发依据，自包含**。
> 上游依据：`M1-unified-datasource-design.md`（统一时间窗）、
> `P1-cross-source-correlation-design.md` 决策 3/4（机械事实 + 时间归一集中在 `RowTimes`）、
> `M7-anomaly-summarization-design.md` §2.2（M7a 因缺基线而只能给绝对计数，本任务解掉这个限制）。
>
> 状态：**设计待评审，尚未开工**。§2.2 有一项**硬前置未确立**，评审时须先决。

---

## 1. 要解决的问题

### 1.1 现状（事实）

`TimeRange.defaultRange()` 的定义是：

```java
public static TimeRange defaultRange() {
    return lastHours(1);   // 最近 1 小时
}
```

它是**相对"现在"的滑动窗口**，与"故障"这件事**没有任何关系**。
`DataSourceToolCallbackFactory` 里所有工具的 `startTime`/`endTime` 缺省时都落到这个窗口。

由此产生三个可观测的问题：

| # | 问题 | 具体表现 |
|---|---|---|
| 1 | **窗口没有锚点** | 模型问"最近的限流日志"，后端给的是"现在往前 1 小时"。而故障可能发生在 3 小时前 —— **模型查了个空窗，然后得出"无异常"**。这正是 `PR.md` 里"没有 log 就随机填充结果"的根因之一 |
| 2 | **没有正常期参照，异常不可判定** | 只有故障期数据时，模型看到"限流 WARN 出现 20 次"。**20 次是多还是少？** 若正常期也是 20 次，这就不是异常。缺基线时模型只能凭语感猜——这是精度损失的主要来源 |
| 3 | **故障余波污染基线** | 即便有人手工给了一个"故障前 1 小时"窗口，若该窗口紧贴故障起点，里面仍含**故障刚开始的爬升段**，基线被抬高，真实异常被稀释 |

### 1.2 目标

> **把"窗口从哪来"和"正常期长什么样"从模型的脑内猜测，变成后端给出的机械事实。**

两件事：

1. **故障窗锚定**：查询窗口以**故障窗**为中心，而不是以 `now` 为中心；
2. **对称正常基线**：在故障窗两侧各构造一个**避开余波**的正常期窗口，并把它与故障期**并排**给模型。

**这一层不做因果推断**——它只回答"故障期 vs 正常期的数值差异是多少"，不回答"为什么"。

---

## 2. 与非目标（边界）——**这一节是本文最重要的部分**

### 2.1 本任务明确**不做**

| # | 不做 | 理由 |
|---|---|---|
| 1 | **不从数据里推断故障时间** | 故障窗是**外部输入**（见决策 1）。从日志/指标反推故障起点＝异常检测，属 M7 职责，且会产生"自证式"循环 |
| 2 | **不让模型自己决定故障窗** | 见决策 1 与 §7 纪律 2。这是本任务**最重要的一条边界** |
| 3 | **不给模型任何调参口** | 偏移量（10min）、基线长度、聚合粒度**不进 tool schema**。理由同 `M7` 决策 3（避免多轮试参数） |
| 4 | **不修改 `TimeRange` 结构** | 见决策 2。`TimeRange` 出现在 `ILogSearchProvider.search` / `IEntityQueryProvider.query` 等**方法签名**中，改它会波及所有 Provider |
| 5 | **不做自动根因排序** | 给"哪个指标变化最大"是算术，"哪个是根因"是因果主张，越界 |
| 6 | **不做跨故障的长期趋势分析** | 本任务只看"本次故障窗 + 相邻两点"。多故障趋势/周期性是独立议题 |
| 7 | **不引入 Metrics TSDB** | 与 M7 同一前置，独立任务（`M4-status §4` P1-3） |
| 8 | **不做前端视图改造** | 前端渲染是独立任务；已有 `P1-3` 双轴时间线可承载 `phase` 分色，但**本任务不动它** |

### 2.2 硬前置（**必须先决，否则本任务无法端到端验收**）

**任务上下文目前不存在。**

M8 要求"故障窗随任务一起传入"，但：

| 环节 | 现状 |
|---|---|
| `AutoAgentRequestDTO` | 只有 `aiAgentId` / `message` / `maxStep` / `sessionId`（`message` 里**可能**含时间描述，但那是自由文本） |
| `ExecuteCommandEntity` | 无时间窗相关字段 |
| 领域层 | **不存在**"故障单/事件"概念，无 `ai_incident` 之类的表 |
| `DynamicContext` | 有 `dataObjects` 可承载，但**无人写入** |

因此需要评审先定**故障窗的来源**，三个候选：

| 候选 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **A. 请求字段**（推荐） | `AutoAgentRequestDTO` 增 `faultStart` / `faultEnd`，随 `ExecuteCommandEntity` 进领域层 | 改动最小，**可立即验收**；无新表；可以先用测试/脚本注入 | 调用方（前端）需传；无历史故障单可回溯 |
| **B. 独立故障单表** | 新增 `ai_incident`（含 `fault_start`/`fault_end`/`prev_end`/`next_start`），Agent 按 `incidentId` 查询 | 最贴合真实排障（故障单是运维事实）；可承载"前次/下次故障" | 需要建表 + 种子 + 前端选择器；**工作量明显更大** |
| **C. 会话内显式声明** | 由 Prompt/工具让模型解析 `message` 里的时间 | **零后端改动** | **明确违反决策 1**：等于让模型决定故障窗，不可接受 |

> **建议**：先用 **A** 打通并验收（对称基线算法与 A/B 无关），B 作为 Phase B 演进（表结构与 A 的字段同构，迁移成本低）。
> **C 不采纳**，理由见决策 1。

**另有一个次级前置**：若要在 UI 上展示"前次/下次故障"以构造更真实的基线，仍需要 B。
候选 A 下的降级规则见决策 4。

---

## 3. 验收

| 验收点 | 判据 |
|---|---|
| 窗口锚定生效 | 请求带 `faultStart=2026-03-27T10:00+08:00` / `faultEnd=11:00` 时，工具未显式传时间窗 → 实际查询落在该窗口内（**不是** `now-1h`）；日志可见 |
| 对称基线产出 | 对比结果里出现 `phase` 标注的**两类桶**：`fault` 与 `baseline`；两侧数值**并排可见** |
| 避开余波 | 前基线的结束时间 = 故障开始 − `offset`（默认 10min），**默认实现里不得等于故障开始** |
| 缺侧如实告警 | 无 `prev_end`（无上次故障）时，前基线的构造方式与降级规则必须在 `warnings` 里**写明**，不得静默 |
| 显式窗优先且留痕 | 模型显式传 `startTime`/`endTime` 时按模型给的执行，并在 `warnings` 记录"本次未使用故障窗（模型显式指定）" |
| Model 面无技术指针 | `toModelJson()` 剥离后无 `rawRef` / `_rawRef`（既有回归网正向断言不变） |
| 不回归 | `mvn -o -B test` 全绿（当前基线 **80 通过**），**既有用例一例不改** |
| 向后兼容 | **不传**故障窗时，行为与今天**完全一致**（`lastHours(1)`），不得因本任务改变既有默认 |

---

## 4. 关键决策

### 决策 1：故障窗**只能来自外部输入**，模型永远无权决定

这是本任务的核心边界。

**理由（三条，按重要性排序）**：

1. **循环论证**：若模型自己推断故障窗，它必然倾向选一个"能证明自己结论"的窗口。这就是选择性偏差（cherry-picking）。基线的作用是**反驳**模型，一旦窗口由被检验者选择，基线就失去裁判资格。
2. **安全边界一致性**：本仓库既有纪律是"`tenantId` 由框架注入、绝不出现在工具 schema 里"（`DataSourceQueryService` 注释）、"模型零拼接权"（`PR.md`）。**故障窗与 `tenantId` 同类——它是框架注入的上下文，不是模型参数。**
3. **可复现**：同一故障单必须得到同一结果。模型的窗口选择不稳定（同 prompt 两次调用可能给两个窗口），破坏可复现性。

**落地含义**：
- 故障窗**不进任何 tool schema**；
- 由 `DataSourceQueryService`（唯一的查询入口）在缺省时注入，与 `tenantId` 同一层，**同一纪律**；
- 模型**仍可**显式传 `startTime`/`endTime`——这是合法的"我要看故障窗之外的数据"，但**必须留痕**（`warnings`）。

### 决策 2：新增 `FaultWindow` 值对象，**不修改 `TimeRange`**

`TimeRange` 是 `ILogSearchProvider.search(LogSearchQuery)` / `IEntityQueryProvider.query(EntityQuery)` 内部
`LogSearchQuery.timeRange` / `EntityQuery.timeRange` 的字段类型，被所有 Provider 消费，且现有单测大量覆盖。

**保持 `TimeRange` 只表达"一个区间"**（单一职责），把"故障 + 前后"的**锚点语义**放进新的 `FaultWindow`：

```
FaultWindow            —— 锚点语义：故障窗 + 前次结束 + 下次开始 + 偏移
BaselineComparison     —— 结果：若干 phase 标注的窗口
```

`FaultWindow` **不含查询逻辑**，只做"由锚点推导出若干 `TimeRange`"的纯计算（可单测，无 IO）。

### 决策 3：基线构造——**避开余波**是硬约束

沿用对标方案的语义（"故障恢复后 40 分钟数据"作为正常期来源；正常期定义为
"上次故障结束后 10 分钟 → 本次故障开始"）：

```
前基线 = [prev_end + offset, current_start)          // offset 默认 10min
后基线 = (current_end + offset, next_start)          // 仅当 next_start 已知
```

**注意开闭区间**：`offset` 段（余波）**必须排除在外**，不是包含。

**为什么是 10 分钟**：可配置，默认 10。它表达的是"故障恢复有尾巴"这一运维事实。
**默认值进配置表，不进 schema**（决策 5）。

### 决策 4：降级规则**必须显式**，不许猜

| 情形 | 处理 |
|---|---|
| 无 `prev_end`（首次故障/未知） | 前基线 = `[current_start − (current_end − current_start), current_start − offset)`<br>即**用故障窗自身长度做镜像**，但**必须在 `warnings` 写明**："未提供上次故障时间，前基线按故障窗长度镜像构造" |
| 无 `next_start` | **不构造后基线**（不猜），并在 `warnings` 写明"无下次故障时间，未构造后基线" |
| 故障窗未结束（排障进行中） | `current_end` 缺失时用 `now`，并写明"故障仍在进行中，故障窗右端取当前时间" |
| 基线长度 < 最小要求 | 不产出对比，`warnings` 写明"基线窗口过短（N 分钟 < 阈值），未产出对比" |

**核心纪律**：**任何降级都必须在 `warnings` 里如实说明**，绝不静默补一个看起来合理的窗口。
这与 `P1-cross-source` 的"缺失侧必须写进 `warnings`、绝不写成'无关联'"是同一条纪律。

### 决策 5：对比结果**复用 `QueryResult`**，一行 = 一个对比窗

与 `P1` 决策 1、`M7` 决策 1 同构。新增一列 `phase` 表达"这一行属于故障期还是正常期"，
**不新增结果类型**。

| 列 | 含义 |
|---|---|
| `phase` | `fault` / `baseline_before` / `baseline_after` |
| `bucketStart` | 桶起点（`yyyy-MM-dd HH:mm:ss`，Asia/Shanghai） |
| `count` / `share` | 该桶条数 / 占比（沿用 M7 归纳口径） |
| `summary` | 桶内摘要（截断） |
| `_rawRef` | 行级原文回指（只进事件版） |

**为什么用 `phase` 分桶而不是给"变化幅度"数字**：分桶是**机械事实**，任何模型都能自行比较；
给出"变化幅度 340%"就已经隐含了"这个变化是异常"的判断。**给事实，不给判断。**

> 若产品需要摘要性的"变化幅度"，它属于 **M7b**，且必须带样本量与基线长度一起给出。
> 本任务只做分桶并列。

### 决策 6：`offset`、基线长度、桶粒度全部**进配置**

散落在代码里的魔法数字是本仓库明确反对的（`M7` 决策 5 同款）。
复用 `ai_data_source*` 的配置化思路，**不进 tool schema**。

---

## 5. 接口

### 5.1 请求侧（候选 A，见 §2.2）

```json
POST /api/v1/agent/auto_agent
{
  "aiAgentId": "4",
  "message": "排查拼团下单失败",
  "maxStep": 3,
  "sessionId": "...",
  "faultStart": "2026-03-27T10:00:00+08:00",   // 新增，可选
  "faultEnd":   "2026-03-27T11:00:00+08:00",   // 新增，可选（进行中可缺）
  "prevFaultEnd": "2026-03-27T04:00:00+08:00", // 新增，可选
  "nextFaultStart": null                        // 新增，可选
}
```

**全部可选**：不传 → 行为与今天完全一致（`lastHours(1)`）。

### 5.2 领域值对象（新增）

```java
// domain/.../model/valobj/datasource/FaultWindow.java
public class FaultWindow {
    private Instant start;
    private Instant end;            // 可空（故障进行中）
    private Instant prevFaultEnd;   // 可空
    private Instant nextFaultStart; // 可空
    private int     offsetMinutes;  // 默认 10
    private boolean faultOngoing;   // end 是否为 now 推导

    public boolean isUsable();                                    // start 必须存在
    public TimeRange faultRange(Instant now);
    public Optional<TimeRange> baselineBefore();                  // prevFaultEnd 缺失时按决策 4 降级
    public Optional<TimeRange> baselineAfter();
    public List<BaselineComparison> comparisons(Instant now);
}
```

```java
// domain/.../model/valobj/datasource/BaselineComparison.java
public class BaselineComparison {
    private String    phase;   // fault / baseline_before / baseline_after
    private TimeRange range;
    private String    note;    // 降级说明，非空时进 warnings
}
```

### 5.3 注入点（**唯一**）

`DataSourceQueryService` 是唯一查询入口（负责 `tenantId` 注入 + tracing）。
故障窗**在同一层注入**——这是决策 1 的落地位置：

| 情形 | 行为 |
|---|---|
| 模型**未**传 `startTime`/`endTime`，且会话有故障窗 | 用 `FaultWindow.faultRange(now)`，**并可能附加基线对比** |
| 模型**显式**传了时间窗 | **尊重模型**（它是显式探索），`warnings` 记录"未使用故障窗" |
| 会话**无**故障窗 | 沿用 `TimeRange.defaultRange()`，**零行为变化** |

> ⚠️ 评审注意：`DataSourceQueryService` 目前是**无状态单例**、方法只有 `dsId` + query 两个入参，
> **没有会话上下文**。注入故障窗需要一条上下文通路（`ThreadLocal` / 显式入参 / `ToolContext`）。
> 本任务**必须**选一条并在此文档评审时确定——这是实现上最脏的一块，不要留到编码时临时决定。

### 5.4 改动清单（预期）

| 文件 | 改动 |
|---|---|
| **新增** `FaultWindow.java` / `BaselineComparison.java` | 值对象 + 纯计算（可单测，无 IO） |
| **新增** `FaultWindowComparisonService.java` | 编排：故障窗 + 基线 → 多次查询 → 按 `phase` 分桶 → 产出 `QueryResult` |
| `DataSourceQueryService` | 缺省时间窗来源改造（§5.3）；**唯一**注入点 |
| `AutoAgentRequestDTO` / `ExecuteCommandEntity` | 新增 4 个可选时间字段（候选 A） |
| `AutoAgentExecuteStrategy` | 把请求里的故障窗放进 `DynamicContext`（`dataObjects` 已有承载能力） |
| `DataSourceCapability` | 新增只读能力 `fault_window_compare` |
| `DataSourceToolCallbackFactory` | 新能力 → 新工具；沿用阶段裁剪 |
| **新增** `upgrade-m8-1-fault-window.sql` | `ai_stage_tool_policy` 新增行（执行阶段 `allowed=1`，其余 0）；配置项；幂等 |
| **新增** `app/src/test/.../test/m8/FaultWindowTest.java` | 纯单测（对称性 / 余波排除 / 四种降级 / 边界开闭 / 时区） |
| `docs/dev-ops/UPGRADE-hot-reload-deepseek.md` | §一 部署顺序新增 SQL 步骤（跳过则工具不挂载，同 P1 的坑） |

**不改**：`TimeRange` 结构、Provider SPI、SSE 事件契约、`QueryResult` 结构、M3 证据派生、
`CrossSourceCorrelationService` 既有对齐逻辑、任何既有单测。

### 5.5 时区

**全部按 `Asia/Shanghai` 解释**，复用 `P1` 已建立的 `RowTimes.BIZ_ZONE`，
**不新增第二套时区口径**。裸 datetime 一律按 `Asia/Shanghai`，解析失败**返回 null 并计数告警**，
**不猜默认时间**（`P1` 决策 4 原文纪律）。

---

## 6. 验证方式（落地后必须补真实证据）

| 层次 | 方式 | 期望 |
|---|---|---|
| 单测 | `mvn -o -B test` | 全绿（81+ 通过），**既有 80 例一例不改** |
| 单测（正向） | 必须有正向断言：给定 `prev_end`/`start`/`end`，`baselineBefore()` **等于**精确区间；`offset` 段**不在内** | 防"空集白过"（`M4-status §10.4` 踩坑） |
| 单测（降级） | 四种降级场景各一例，断言 `note` **非空** | 防静默降级 |
| 向后兼容 | **不传**故障窗跑既有集成用例 | 结果与改造前一致 |
| 端到端 | 真实 ES + MySQL + LLM，`aiAgentId=4`，故障窗 `2026-03-27 10:00~11:00` | 工具实际查询落在故障窗内（非 `now-1h`）；结果含 `fault` 与 `baseline_before` 两类桶 |
| 端到端（对照） | 同一请求，模型显式传 `startTime` | 按模型窗口执行，`warnings` 有"未使用故障窗"记录 |
| 回归 | `web/scripts/render-check.cjs` | ALL PASS（`phase` 是新列，**前端未适配前不应崩**——需确认前端对未知列的处理，若会崩则列入本任务范围） |

**反例实验（必须做）**：把故障窗设成一个**明显错误**的窗口（如 3 天前），确认
**基线对比如实反映"这个窗口里也没异常"**，而不是被模型"解释圆"。
→ 证明本层给的是**事实**而非**背书**。

---

## 7. 纪律（不可破，沿用并新增）

1. **只讲机械事实**：输出"故障期 37 条、前基线 2 条"是事实；"因此是限流导致"不是。
2. **故障窗由框架注入，绝不进 tool schema**——与 `tenantId` 同一条纪律（`DataSourceQueryService` 注释）。
3. **任何降级必须写进 `warnings`**，绝不静默补窗口、绝不猜时间。
4. **`rawRef` / `_rawRef` 永不进入模型提示词**，仍由 `QueryResult.toModelJson()` 剥离，**契约不变**。
5. **余波段必须排除**（开闭区间要在单测里钉死）。
6. **不猜参数**：`offset`、基线长度、桶粒度进配置。
7. **不修改 `TimeRange`**、不修改任何既有单测。
8. **`domain` 不依赖 `infrastructure`**；查询只经 `DataSourceQueryService`。
9. **V1 只经 PR 合入**，禁止直接提交。

---

## 8. 已知限制（**未做**，如实登记）

1. **故障窗来源未定**（§2.2）。候选 A 只是**建议**，评审须先决；B 是 Phase B。
2. **注入通路未定**（§5.3）。`DataSourceQueryService` 当前无会话上下文，这是实现上最脏的一块。
3. **无 Metrics TSDB**：本任务的基线对比只作用于**日志与实体记录**；数值型指标（CPU、延迟）
   的"故障期 vs 正常期均值对比"属 **M7b**，依赖 Loki/Prometheus。
4. **只处理"本次 + 相邻两点"**：不处理周期性故障、不处理级联故障的多次叠加。
5. **不给"变化幅度"数字**（决策 5）：产品若需要，属 M7b，且必须带样本量。
6. **不做多条故障并联对比**：一次请求一个故障窗。
7. **前端 `phase` 列未适配**：`P1-3` 的双轴时间线可自然承载"故障/基线"分色，但**本任务不动前端**。
   若未知列会导致前端崩溃，则该适配**必须**并入本任务（已在 §6 标注为待确认）。
8. **未验证**：本文全部为设计，§3 的验收判据**一条都还没有实测证据**。落地后如实回填，
   达不到的判据要改判据或改设计，**不要改结论**。

---

## 9. 参考

| 依据 | 位置 |
|---|---|
| 统一时间窗与 `defaultRange()` | `M1-unified-datasource-design.md`、`TimeRange` |
| 时间归一与时区口径（`RowTimes.BIZ_ZONE`） | `P1-cross-source-correlation-design.md` 决策 4 |
| "只做机械对齐/不做因果推断" | `P1-cross-source-correlation-design.md` 决策 3 |
| "缺失侧必须写进 warnings" | `P1-cross-source-correlation-design.md` §7-1、§2 纪律 |
| `QueryResult` 复用与 `rawRef` 双通道 | `M3-evidence-panel-design.md`、`QueryResult.toModelJson()` |
| `tenantId` 框架注入纪律 | `DataSourceQueryService` 类注释 |
| "空集上白过"踩坑 | `M4-status-and-next-tasks.md` §10.4 |
| M7（本任务的消费方，M7b 阻塞于此） | `M7-anomaly-summarization-design.md` §2.2 |
| Metrics 源为 P1-3 候选 | `M4-status-and-next-tasks.md` §4 |

---

## 10. 实施决策（2026-10-17 定案，落地前先看这一节）

> 三项待定决策已由负责人在考察现有代码后拍板，**落地以本节为准**；与上文冲突处以本节为准并回填 GAP §3.0。

| 项 | 定案 | 依据（考察所得） |
|---|---|---|
| **D-1 故障窗来源** | **A 请求字段 + 告警适配器**：`AutoAgentRequestDTO` 增 `faultStart` / `faultEnd` / `prevFaultEnd` / `faultLabel`（均可空、ISO-8601）；新增 `POST /api/v1/incident/alert` 把 Alertmanager 风格 `{startsAt,endsAt,labels}` **归一成这四个字段**（无新表）；`incidentId` 预留、B（表）作 Phase B | 仓库里**没有**任何告警系统可对接，"告警接入"只能是**适配器**（把告警变成窗口），不该为此先建表；请求字段是**唯一真相**且可立即验收 |
| **D-2 注入通路** | **`ToolContext` 传输 + `DataSourceQueryService` 收显式参数**；提示词侧用**上下文块**（故障窗/基线/`phase` 语义）作为补充 | `AbstractExecuteSupport.toolCallContext()`（`:89-96`）已在构造 ToolContext map（放 `ToolCallEventSink`），加窗口 2 行；`ThreadLocal` 在 `threadPoolExecutor` + 流式下有**串请求**风险，拒绝 |
| **D-4 前端 `phase` 列** | **后端先出列**（`phase`=fault/baseline）+ **前端独立小 PR**（分色/列名映射） | `web/src/components/evidence/TableView.tsx:20` 是 `columns` 驱动渲染 → **未知列自动显示、不会崩**；因此前端不阻塞后端验收 |

**一处对 §5.3 的有意偏离（需评审确认）**：§5.3 原计划让 `query_*` 工具在"模型未传时间窗"时**自动**改用故障窗。
考察后改为**新增独立工具 `query_fault_baseline`**（一次调用返回 fault/baseline 两相分桶对比），理由：
① 不改既有工具的**缺省语义**（否则同一问题在不同请求上下文下行为漂移，难测也难解释）；
② 模型对"我在看基线对比"有**明确认知**（工具名与 description 直说），不会被"窗口悄悄换了"误导；
③ 仍满足"窗口不进 schema"的纪律（工具不接受窗口参数，窗口来自 `ToolContext`）。
既有 `query_logs`/`query_orders`/`query_payments`/`query_correlated_timeline` **零改动**。

**实施顺序（每步可独立验收）**
1. 请求字段 + `FaultWindow` 值对象（纯函数：窗口/基线/四种降级/warnings）+ 单测；
2. `FaultWindowComparisonService` + 新工具 `query_fault_baseline` + 幂等 SQL（阶段策略）；
3. 提示词上下文块（消息模板）+ 真实对话验收（含"无基线"降级）；
4. 前端 `phase` 分色（独立 PR）。
