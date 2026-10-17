# PR：fix(web) 多轮会话追加式保存（修复同会话旧记录被清空）+ M4 交接文档

**base**: `V1-for-secondary-development` ← **head**: `feat/p1-3-timeline-view`
**创建链接**: https://github.com/311miaowa/Troubleshooting-Agent/compare/V1-for-secondary-development...feat/p1-3-timeline-view?expand=1

> ⚠️ **本 PR 与已合并的 PR #17 是同一分支的「后半段」。**
> PR #17（`3f7c6fc`，2026-09-26 合并）只并入了本分支的**第 1 个**提交 `7d5fc85`（双轴时间线视图 + SSR 渲染检查）。
> 之后推送的 **3 个提交**（多轮丢记录修复 + 交接文档 + 交接 prompt）**不在 V1 中**，由本 PR 承载。
> `git merge-base --is-ancestor 94478d5 3f7c6fc` → 退出码 `1`（可复现）。
>
> 因此本 PR 的 diff 是「V1 相对本分支」的**剩余差量**，**不含** PR #17 已合并的时间线视图部分。

---

## 一、解决什么

### ① 多轮会话丢记录（严重缺陷修复，本 PR 唯一的代码改动）

同一会话里连续问两个问题，界面只剩最后一个问题的记录：第一个问题的提问、证据、结论全部消失
（刷新后依旧，因为持久化里已经被覆盖）。

**根因 100% 在前端**，与后端无关。`web/src/store/useSessions.ts` 的 `run()` 每次提问都把会话当成单轮重置：

| 字段 | 修复前 | 后果 |
|---|---|---|
| `question` | 直接覆盖 | 旧问题文本没了 |
| `title` | `s.question ? s.title : makeTitle(question)` | 标题退化成最后一个问题 |
| `events` | `events: []` | 旧事件流整条丢弃 → 证据 / 时间线 / 结论全丢 |

即事件流不是追加的，而是每轮重建。这与 M3 设计文档 §4/§0 的「追加式事件流、可回看」前提直接冲突。

> 模型侧的多轮上下文**本来就是好的**（后端 `PromptChatMemoryAdvisor` + `MessageWindowChatMemory(200)`，
> 经 step 节点 `chat_memory_conversation_id = sessionId` 绑定），本 PR 不动它。

### ② M4 交接文档（纯文档，无代码影响）

- `docs/dev-ops/M4-status-and-next-tasks.md`（新增）：状态快照 + 未完成任务清单（带优先级/代码入口）+ 验证基线 + 本机环境坑。
- `docs/dev-ops/NEXT-SESSION-PROMPT.md`（新增）：下一会话交接 prompt（默认收尾版 / 指定任务版 / 上下文管理版）。
- `docs/dev-ops/M3-evidence-panel-ui-design.md`：新增 **§8**（多轮丢记录的根因 / 修复 / 派生规则 / 边界）。
- `docs/dev-ops/M4-datasource-health-design.md`：状态补充。

> 已知：上述两份新增文档写于 PR #17 创建**之前**，其中「PR 未创建 / 覆盖 2 个提交 / 分支 @5806ea7」
> 三处表述在 PR #17 合并后已过期。本 PR 先按原样合入以保留当时的交接上下文，更正另提。

## 二、改了什么

| 文件 | 改动 |
|---|---|
| `web/src/types/session.ts` | 新增 `Turn{question,at}` 与 `Session.turns`（追加式）；`question`/`title` 仅表示首轮 |
| `web/src/store/useSessions.ts` | `run()` 只追加 `turns`、保留 `events`，不再重置；`load()` 迁移只有单个 `question` 的旧数据 |
| `web/src/utils/investigation.ts` | 证据/时间线看**全会话**，结论/诊断/耗时只看**当前轮**；Action 带 `turn` 归属；多轮时把每个问题画成 `USER QUESTION` 节点 |
| `web/src/components/Workbench.tsx` | 结论/诊断/耗时改用当前轮事件切片；时间线传 `turns` |
| `web/src/components/HistorySidebar.tsx` | 历史轮的问题也可搜索 |
| `web/scripts/smoke.ts` | 新增 **8 项**多轮断言 |
| `web/scripts/render-check.tsx` | 新增 **3 项**多轮 SSR 渲染断言 |
| `web/.gitignore` | 忽略构建产物 `scripts/render-check.cjs` |
| `docs/dev-ops/M3-…`（§8）、`M4-status-and-next-tasks.md`、`NEXT-SESSION-PROMPT.md`、`M4-datasource-health-design.md` | 文档与状态更新 |

**关键设计**

1. **追加式**：新问题只 append，不清空、不覆盖 —— 历史轮的问题 / 证据 / 结论全部留得住。
2. **分层取值**：证据与时间线必须看全会话（可回看），结论 / 诊断 / 耗时必须只看当前轮
   （否则"取最后一条 summary"会被上一轮更长的文本抢走）。
3. **防串轮**：每个 Action 带 `turn`，`UNDERSTAND INCIDENT` 用 `turn + step` 去重、`DIAGNOSE` 按 `turn` 去重 ——
   因为 `step` 每轮都从 1 重新计数，只用 `step` 会把第二轮的「理解问题 / 结论」并进第一轮（另一种形态的信息丢失）。
4. **轮次可见**：多轮时把每个问题本身画成时间线节点 `USER QUESTION` / `USER QUESTION #N`，作为各轮之间的天然分隔。

## 三、ES 只读守卫（`EsQueryGuard`，第二个代码改动）

ES 侧此前**缺 SQL 侧的对等防护**：`PhysicalName` 直接拼进 URL 路径、
`query_template` 渲染结果直接作为请求体，两者都来自库表 `ai_data_source_entity` 且未校验。
新增 `EsQueryGuard` 在发请求前做三件事：

| 校验 | 规则 |
|---|---|
| `physical_name` | 标识符正则：仅小写字母/数字/`._-`/`*`，不得以 `_` 或 `.` 开头，拒裸 `*`；拒绝 `/` `\` `:` `?` `,` `#` `%`、空白、控制字符、大写 |
| 渲染后的 DSL | 顶层键白名单 + 任意层级禁键（`script`/`script_fields`/`runtime_mappings`/`doc`/`upsert`/`update`/`pipeline`） |
| 边界 | `size`/`from` 非负且 ≤ 10000 |

**范围说准**：请求方法与路径本就硬编码为 `POST /{index}/_search`，应用内到不了
`_bulk` / `_delete_by_query` 等写端点；本守卫收的是「`physical_name` 未校验」与
「DSL 渲染结果未校验」这两个真实口子。**它不是权限系统，不能替代 ES 服务端安全特性。**

### 顺带核查：ES MCP 写删工具其实没有暴露给模型

原任务把「ES MCP 暴露 6 个写/删类工具」列为待修。实测核查（MCP `tools/list` + 库表 + 运行时日志）结论：
ES MCP server **确实**暴露 11 个工具（读 5 + 写删 6，工具名与原文完全一致），库表 5007 与 7 条绑定也都
`status=1`、启动时 MCP 进程确实握手成功；**但运行时 9 个客户端的工具面 MCP 工具数 = 0**
（`getMcpBeanNameList()` 零调用方、`SyncMcpToolCallbackProvider` 只在 `src/test`）。
**即潜在风险，非现行暴露。** 故"MCP 客户端层白名单"本次未实施——它对当前 ES 查询路径
（直连 `_search`，不过 MCP）也无保护作用。详见 `ES-MCP-exposure-verification.md`。

## 四、验证（本机实测输出，非引用文档）

```
mvn -o -B test                                          →  Tests run: 80, Failures: 0, Errors: 0  BUILD SUCCESS
                                                            （61 基线 + EsMcpToolExposureTest 6 + EsQueryGuardTest 13）
npm.cmd run build                                       →  exit 0（含 tsc -b）
node scripts/.smoke.mjs                                 →  ALL PASS，[PASS] 90 项，[FAIL] 0 项
node scripts/render-check.cjs                           →  RENDER ALL PASS，[PASS] 14 项，[FAIL] 0 项
GET 8099/api/v1/datasource/health                       →  {"total":3,"healthy":3,"unhealthy":0}
```

**守卫的端到端实测（真实后端 + 真实 ES，非 mock）**：

```
java -jar ... --server.port=8098
POST /api/v1/agent/auto_agent {"aiAgentId":"4","message":"请检索最近的限流日志…","maxStep":3}
→ HTTP 200，SSE 33875 bytes
→ 工具事件：query_logs x36、describe_entity x2、query_correlated_timeline x4
→ 命中 ds_log_es、匹配"限流"
→ 守卫告警数 = 0（未拦任何正常查询）
```

**对照组**：V1（`3f7c6fc`，未含本 PR）实测同一前端命令为 `smoke 82 项 / render-check 11 项`。
差额 `90-82 = 8`、`14-11 = 3`，与提交信息中「smoke +8、render-check(SSR) +3」一致。

多轮专项断言（smoke）：`turns` 追加、`run()` 不清空 `events`、旧数据迁移成一轮、
`buildConclusion` 取当前轮最后一次完成的 summary、两轮同名 `DIAGNOSE` 不合并、`USER QUESTION #2` 出现、
历史侧栏能搜到旧轮问题。渲染专项断言：多轮会话把两轮问题都渲染在时间线上，且两轮结论都在 DOM 里。

## 五、reviewer 注意事项 / 已知边界

1. **交互未自动化**：点击/悬停/键盘可达性仍需人工在 `npm run dev`（5173）上看；SSR 检查只覆盖初始渲染。
   要自动化需引入 playwright（会显著增加前端依赖，本 PR 未做）。
2. **多轮修复的边界**：本次只保证**前端不丢信息**；localStorage 仍是单浏览器、无版本快照、无跨 session 检索，
   服务端会话持久化仍是 M6。模型侧上下文管理（ChatMemory 为每 JVM 内存态、重启即失、多实例不共享）是独立课题，本 PR 不动。
3. **诊断可锚定到更早轮的证据**：结论取自当前轮，但它引用的数据行可以在更早一轮取过 —— 这是诚实映射，不是串轮。
4. **后端零改动**：本 PR 不碰后端、不改 SSE 契约。
5. 本 PR 的描述文件 `p1-3-pr-body.md`（PR #17 用的那份）**保持原样未改**，以保留当时的评审上下文；
   实际提交清单见下方。

---

### 提交清单（6 个）

```
5806ea7 fix(web): 多轮会话追加式保存，修复同会话旧记录被清空
0cdf10e docs: 新增 M4 交接与后续任务清单（状态快照 + 未完成任务 + 验证基线 + 环境坑）
94478d5 docs: 下一会话交接 prompt（默认收尾版 + 指定任务版 + 上下文管理版）
f87ca3c docs: 交接文档补 §8 新发现（SSE 客户端断开错误处理不干净 / 后端无异常退出未确认）+ §9 运行进程
2d7b1fd test(p1): 钉住模型可见工具面不含 ES MCP 工具（6 例，默认运行）+ 核查结论与文档校正
b978fdb feat(p1): ES 只读守卫 EsQueryGuard（SqlTemplateRenderer 的 ES 侧对等物）+ 13 例单测
```

> 复现命令：`git log --oneline origin/V1-for-secondary-development..feat/p1-3-timeline-view`
> （实测输出即上表 6 行；`git merge-base --is-ancestor 94478d5 3f7c6fc` → 退出码 `1`，
> 证明 PR #17 的合并提交 `3f7c6fc` 不含后 5 个提交。）
