# P1-3 交付：跨源对齐的前端双轴时间线视图

> 本文档是 **P1-3 的交接依据，自包含**。上游依据：
> `docs/dev-ops/P1-cross-source-correlation-design.md` §7.6（"前端没有专属双轴时间线视图"这一条边界）
> 与 §8 的 Phase B 候选第 1 项。
>
> 状态：**已完成**（分支 `feat/p1-3-timeline-view`，从 V1 `f55f3fd` 切出；PR 待开）。

---

## 1. 要解决的问题

后端（P1）已经把"日志时间线 ↔ 实体时间线"按时间桶对齐好了，但前端只是把它当**普通表格**渲染
（列 = `bucketStart/logCount/orderCount/logSummary/orderSummary`）。于是"哪一分钟里两侧同时有数据"
仍要使用者**逐行读表**才能看出来——正是 P1 想消灭的那种"靠人比对"。

**目标**：把对齐结果画成**一眼可见的时间轴**：横轴 = 时间，纵轴 = 泳道（数据侧），
每个时间桶画一条贯穿所有泳道的竖线；两侧都有数据的桶高亮。

## 2. 验收与结果

| 验收点 | 结果 |
|---|---|
| 证据面板按类型分派到时间线视图 | ✅ `kind === 'cross'` → `TimelineView`（与 LogView/TableView 同级） |
| 双泳道 + 时间轴 | ✅ 泳道名 `日志` / 对齐的实体名（如 `支付流水`），横轴按桶起点（Asia/Shanghai）比例定位 |
| "同一时间桶"一眼可见 | ✅ 每桶一条贯穿竖线；两侧都有数据的桶 = 高亮竖线（2px 蓝）+ 两个高亮标记 |
| 桶内可回指原文 | ✅ 点击标记展开该桶：两侧摘要 + 逐条 `_rawRef`（**可复制**；ES 文档在配了 `VITE_KIBANA_BASE` 时可**直接跳 Kibana**） |
| 诚实呈现 | ✅ 某侧 0 条 → 明确告警"不等于无关联"；桶时间解析失败 → 排除在轴外 + 计数告警；两侧都有 → 标注"时间上吻合（不代表因果）" |
| N 源而非硬编码 2 | ✅ 泳道由列名 `<lane>Count` / `<lane>Summary` **自动识别**，将来加第三泳道不用改视图 |
| 不回归 | ✅ 前端 `npm run build` exit 0；`smoke.ts` **77 项全 PASS**（原 61 + 新增 16 项时间线断言）；`render-check.tsx` **11 项全 PASS** |

## 3. 设计与实现

### 决策 1：纯逻辑抽到 `utils/timeline.ts`，组件只负责画

`web/package.json` 没有 playwright/puppeteer，交互（点击/悬停）无法自动化。因此把**所有能算的东西**
（泳道识别、时间解析、轴跨度、位置归一、刻度、告警）都放进纯函数 `buildCrossTimeline(...)`，
由既有的 `scripts/smoke.ts` 覆盖；组件 `TimelineView.tsx` 只做渲染。这样"会不会算错"由测试守，
"画得对不对"由 SSR 渲染检查守（见决策 3）。

> 命名带 `Cross` 是为了与既有的 `investigation.buildTimeline`（调查过程时间线）区分。

### 决策 2：泳道从列名推导，而不是写死"日志/订单"

```
columns: [bucketStart, logCount, orderCount, logSummary, orderSummary]
                    └─ laneId = log      └─ laneId = order
```

- `log` → 展示名"日志"；`order` / `entity` → 展示名取**对齐的那一侧实体名**
  （从工具入参 `entityCode` 反查数据源清单里的 `entityName`，如 `payments → 支付流水`）。
  这一步是必要的：P1-2 之后"实体侧"可能是订单、支付流水等，写死"订单"会**误导**。
- 认不出列名时退化为单泳道并告警（不静默画空）。

### 决策 3：用 `react-dom/server` 做渲染检查（补上"前端不验渲染"的缺口）

新增 `web/scripts/render-check.tsx`：用 SSR 静态渲染真组件并断言渲染结果——泳道标签、
标记数量（= 各桶有效侧之和）、高亮竖线与高亮标记数、时间刻度、"对齐来源 + 桶粒度 + 两侧条数"、
以及"未点击时不展开详情""没有假告警"。它不能替代像素级/交互测试，但能抓住"组件根本没画出来/
props 传错/JSX 写错"这类问题。

```powershell
cd web
node_modules\.bin\esbuild.cmd scripts/render-check.tsx --bundle --platform=node --format=cjs `
  --jsx=automatic --define:import.meta.env={} --outfile=scripts/.render-check.cjs
node scripts/.render-check.cjs     # → RENDER ALL PASS
```

> 注意必须用 `--format=cjs`：`react-dom/server` 是 CJS，esm 打包会报 `Dynamic require of "stream"`。

### 交互与呈现细节

- 标记：小圆点（条数 > 1 时在点内显示条数）；悬停出 Tooltip（桶起点 + 两侧条数 + "时间上吻合"提示）。
- 点击标记 → 下方详情块：桶起点、`两侧都有数据（时间上吻合，不代表因果）` / `仅单侧有数据`、
  每侧一行摘要（截断 + 原生 title 全文）、原文引用列表（复制 / 跳 Kibana）。
- 视图自身也直接写出"对齐了谁 + 桶粒度 + 各侧条数"，不必翻到下面对 Interpretation 才能看明白。
- Inspector 的 Result 段对 `cross` 证据改说"N 个时间桶"（而不是"N matched"）。

## 4. 接入点（改了什么）

| 文件 | 改动 |
|---|---|
| **新增** `web/src/utils/timeline.ts` | 纯逻辑：`buildCrossTimeline` / `parseBucketTime` / `detectLaneIds` / `positionOf` / `axisTicks` / `parseBucketMinutes` / `parseEntityCode` / `hhmmss` |
| **新增** `web/src/components/evidence/TimelineView.tsx` | 双轴时间线视图（泳道 + 贯穿竖线 + 标记 + 桶详情 + 如实告警） |
| `web/src/components/EvidencePanel.tsx` | `kind === 'cross'` → `TimelineView`（并把 `sources` 传进 Inspector）；Result 段对 `cross` 说"N 个时间桶" |
| `web/src/styles/theme.css` | 新增 `.tl-*` 样式（轴/泳道/标记/高亮/详情/引用），沿用既有设计 token |
| `web/scripts/smoke.ts` | 新增 16 项时间线断言 |
| **新增** `web/scripts/render-check.tsx` | SSR 渲染检查 11 项（可重跑，见决策 3） |
| `docs/dev-ops/P1-cross-source-correlation-design.md` | §7.6 边界标记为已补齐 |
| `docs/dev-ops/M4-datasource-health-design.md` | §8 的"前端渲染级回归缺失"更新为"已有 SSR 渲染检查（仍无像素/交互自动化）" |

**不改**：后端（P1/P1-2 的接口与数据结构）、SSE 契约、M3 证据派生逻辑。

## 5. 验证证据

```
npm run build                        → exit 0（含 tsc -b 类型检查）
node scripts/.smoke.mjs              → ALL PASS（77 项；其中时间线 16 项）
node scripts/.render-check.cjs       → RENDER ALL PASS（11 项）
```

时间线单测覆盖：泳道识别 / 实体泳道用对齐实体名 / 两侧条数按行统计 / 桶时间解析且严格升序 /
解析失败桶排除在轴外并计数告警 / 桶宽取自入参 / 位置归一化到 0~1 / 刻度含末桶且不超上限 /
桶级引用按 `|` 拆开 / "两侧都有"判定 / 单侧 0 条告警（含"不等于无关联"）/ 单桶不除零 /
认不出泳道退化为单泳道 / 入参解析容错 / `HH:mm:ss` 截取。

渲染检查覆盖：对齐来源、两条泳道标签、实体泳道用实体名、桶粒度与两侧条数、时间刻度、
标记数量、高亮数量、未点击不展开、点击提示、无假告警。

**真实数据形态**：断言用的 fixture 直接取自 P1/P1-2 的**真实 SSE 抓包**
（`data/m4-evidence/p1-sse.txt`、`p1-2-sse.txt`），不是凭空构造。

## 6. 边界与已知限制

1. **交互未自动化**：点击/悬停/键盘可达性仍只能人工在 `npm run dev`（5173）上看；
   SSR 检查只覆盖初始渲染。要自动化需引入 playwright（会显著增加前端依赖，未做）。
2. **没有"会话级时间线"**：当前只在**单条跨源证据**的 Inspector 里画时间线。
   把本次调查里所有日志/订单/支付证据合并成一条总时间轴，是另一个更大的视图（未做）。
3. **轴按桶起点均匀锚定**：桶是等宽的，因此刻度是"桶位置"而不是"真实时间比例"
   （相邻桶间隔不等时视觉上会等距）。要严格按时间比例需要另画（当前实现更适合"看对齐"）。
4. **不做因果判断**：只高亮"同一桶内两侧都有数据"，并在文案里明确"不代表因果"（沿用 M3/P1 纪律）。
5. **`_rawRef` 逐条展示但未做批量导出**；复制是逐条复制。
6. **深色主题专用**：`.tl-*` 用的是既有设计 token，未额外适配浅色主题（本项目只有深色主题）。

## 7. 后续可做

| 候选 | 价值 |
|---|---|
| 会话级总时间线（合并本次调查的全部证据源） | 从"看一条证据"升级为"看整场调查" |
| 真时间比例轴 + 缩放/平移 | 桶间隔不等时更准确；大窗口更可读 |
| 引入 playwright 做交互/像素回归 | 把"点选、悬停、键盘可达"也纳入自动化 |
| 泳道侧按 `entityCode` 分色（多实体并排） | 与"多源联合对齐"（P1 §8）配套 |
