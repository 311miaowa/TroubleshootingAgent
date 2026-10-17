# M4 详细设计：数据源健康可见化（Data Source Health & Provenance）

> 本文档是 **M4 的开发依据，面向新会话自包含交接**。
> 新会话请先读本文档全文，再读 `docs/dev-ops/M1-unified-datasource-design.md` 的「0. 交接信息」
> （工具链坑、环境事实都在那里），必要时读 `docs/dev-ops/M3-evidence-panel-design.md`（SSE/证据契约）。
>
> 状态：**实施完成（后端已合入 + 前端已完成）**。4 个关键决策**已拍板，全选推荐项 A**。
> M4.0a/M4.0b/M4.1 已合入 V1（PR #11，V1 = `be387bb`）；M4.2 在分支 `feat/m4-2-ui-health` 上完成（证据见 §0.4.5），PR 待开。
> 注：UI 重构（PR #10）也已合入，M4.2 的分支即从合入后的 V1 切出。

---

## 0. 交接信息（现状与起点）

> **⚠️ 本仓库的公开快照不包含内部交接文档**（`M4-status-and-next-tasks.md`、开发计划与面试准备等）：
> 本文以下内容为 M4 本体（数据源健康检查）的设计与验证记录，可独立阅读。
> 引用到交接文档的地方已改为纯文字说明。

### 0.1 里程碑状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 配置热更新修复 + DeepSeek 解耦 | ✅ 已合入 V1（PR #1/#2） |
| M1（M1.0~M1.4） | 统一数据源接入层 + 工具层治理 | ✅ 已合入 V1（PR #3/#4/#5） |
| M3（M3.0~M3.2） | 证据面板后端：rawRef 落地 + Tool Call 事件 + 事件/rawRef 打通 | ✅ 已合入 V1（PR #6/#7/#8） |
| M3.3 | 前端证据面板（`web/`） | ✅ 已合入 V1（PR #9） |
| UI 重构 | 按 UI 设计文档重构为 Investigation / Evidence / Diagnosis 工作台 | ✅ 已合入 V1（PR #10） |
| **M4.0a** | 根因确认（受控复现 + 对比实验） | ✅ **已完成**（根因**已证实**，见 §0.4.1） |
| **M4.0b** | 启动期健康检查 + 可操作 WARN（无对外端点） | ✅ **已完成**（验证见 §0.4.2） |
| **M4.1** | 健康端点 + 缓存 + `?refresh=true` | ✅ **已完成**（验证见 §0.4.3） |
| **M4.1 合并** | 后端整包 PR | ✅ **已合入 V1（PR #11）**，V1 = `be387bb` |
| **M4.2** | 前端接权威状态（分支 `feat/m4-2-ui-health`） | ✅ **已完成并合入 V1（PR #12）**（验证见 §0.4.5） |
| **M4.3** | 单测 + 文档 + PR | ✅ **已完成**（后端单测 14/14、前端 smoke 18 项断言）；后端经 PR #11 合入，前端经 PR #12 合入 |
| **P0** | T1 测试工具链 + T2 热更新删除检测（分支 `feat/p0-test-and-hotreload`） | ✅ **已完成并合入 V1（PR #13）** |
| **收尾小修** | `main` 传 args（④）+ hint 空档（⑪）+ 文档修订（⑬）（分支 `chore/main-args-hint-docs`） | ✅ **已合入 V1（PR #14）** |
| **P1①②** | 跨源关联 + 第三个数据源（PostgreSQL 支付流水） | ✅ **已合入 V1（PR #15 / #16）** |
| **P1③** | 前端双轴时间线视图 + 多轮会话记录丢失修复（分支 `feat/p1-3-timeline-view`） | 🔄 **已推送，PR 待创建** |
| **交接** | 状态与未完成任务清单（`M4-status-and-next-tasks.md`，**内部文档，不随公开快照发布**） | — |

### 0.2 分支现状（**开工前必读**）

```
V1-for-secondary-development = da9d5dd   ← 集成分支（只经 PR 合入，禁止直接提交）
  ├─ feat/ui-investigation-redesign = 7a661b3  ← UI 重构，已推送 origin，PR 未开
  └─ feat/m4-datasource-health                ← M4 后端分支（从 da9d5dd 切出，本文件所在分支）
```

- 本地 `V1-for-secondary-development` 已与远端同步（`da9d5dd`），**不是**陈旧的 `3240ff7`。
- **⚠️ 依赖**：M4.2（前端接权威状态）改的是 UI 重构后的组件（新 `DataSourceMenu` / `Workbench`）。
  - 若 UI PR 先合入 V1 → M4.2 直接基于 `V1-for-secondary-development`（或在本分支上 rebase）；
  - 若 UI PR 尚未合入 → M4.2 需基于 `feat/ui-investigation-redesign`，或先合 UI PR 再开工。
  - **M4.0/M4.1（后端）不依赖 UI**，已在 `feat/m4-datasource-health` 上开工（本分支从 `da9d5dd` 切出）。
- 本文档已随 M4 分支提交（原为 UI 分支工作区的 untracked 文件，见 §6 M4.0a）。

### 0.3 环境事实（沿用 M1 §0，此处只列最容易踩的）

| 事实 | 说明 |
|---|---|
| 编译 | `$env:JAVA_HOME='D:\develop\IntelliJ IDEA 2025.3.2\jbr'`，然后 `mvn -o -B -DskipTests test-compile`（离线） |
| 跑测试 | **`mvn -o test` 现在能跑了（T1 已修）**：surefire `2.6` → `2.22.2`（离线仓库仅此版本有 `surefire-junit4`）+ 去掉硬编码 `skipTests=true` + 排除需外部服务的集成测试 → **28 个纯单测全绿**。集成测试可用 `-Dtest=Xxx` 按需单跑（需完整环境；`@SpringBootTest` 在受限沙箱下起不来，放宽到 `danger-full-access` 即可）。见 §6.3 |
| 手工跑 JUnit4 | 仍可用 `JUnitCore` + 手工 classpath（M3/M4 既有姿势，见 §6.2）。**坑**：fastjson 2.0.28 需同时带 `fastjson2` 与 `fastjson2-extension`；`ToolContext`/`ToolCallback` 在 `spring-ai-model` |
| 起后端 | `java -jar ai-agent-station-study-app/target/ai-agent-station-study-app.jar`，dev profile 端口 **8099**；`mvn -o -B -DskipTests package` 产出该 jar |
| **✅ CLI 参数已生效**（曾静默失效，已修） | **历史坑**：`Application.main` 原是 `SpringApplication.run(Application.class)`——**没传 `args`**，所以 `--key=value` 形式的命令行参数被**静默忽略**（M4 实测：`--server.port=8098` 仍绑 8099、`--xfg.datasource.health.ttl-ms=5000` 不生效）。**已修复**（分支 `chore/main-args-hint-docs`）：改为 `run(Application.class, args)`，实测 `--server.port=8098` 成功绑 8098。覆盖属性仍可用 `-Dkey=value` 系统属性 / 环境变量 / `SPRING_APPLICATION_JSON` |
| **订单源密码** | `ai_data_source.conn_config.passwordEnv = DS_ORDER_MYSQL_PASSWORD`，**必须注入环境变量**（仓库不放口令，自行设置），否则订单源不可用（这就是 M4 要治的病） |
| 起前端 | `cd web && npm run dev`（端口 5173）。npm 缓存需重定向：`$env:npm_config_cache=<repo>\.npm-cache-m3` |
| **前端命令要用 `npm.cmd`** | 本机 PowerShell 执行策略禁止 `npm.ps1`（`running scripts is disabled`）→ 一律用 `npm.cmd` / `node_modules\.bin\*.cmd` |
| **前端构建需放宽沙箱** | `npm run build` 里 `vite build` 会让 esbuild 以**管道 stdio** 起子进程，`workspace-write` 下报 `spawn EPERM`（沙箱边界）→ 需放宽到 `danger-full-access`；`tsc -b` 本身不受影响。另：用 `2>&1 | Select-Object` 管道跑它时退出码不可信，请用 `*>` 重定向后再看 `$LASTEXITCODE` |
| 前端验证 | `npm run build`（含 `tsc -b`）；纯逻辑回归跑 `web/scripts/smoke.ts`；真实数据流水线跑 `web/scripts/verify-real.ts`（用法见文件头注释） |
| **git push** | 本机走代理 fake-ip（`github.com` 解析到 `198.18.0.15`），直连会 `Connection was reset`。**必须走本地代理**：`git -c http.proxy=socks5h://127.0.0.1:7897 push ...`（用 `http://` 会 schannel 握手失败） |
| 沙箱 | 本机 `workspace-write` 沙箱 runner 不可用（报 temp 目录缺失），多数命令需放宽到 `danger-full-access` |

### 0.4 M4 要治的病（**症状已观测，根因已证实**）

**症状**：一次真实调查中，Agent 连续 3 次调用 `query_orders`，全部失败：

```
EV-01 [Database Query] 订单 · ai_order · 0 hits / 0 rows · 5021ms
   QUERY: create_time ∈ [...] AND limit = 50
   INTERP: 查询未返回数据：MySQL 查询异常：Failed to obtain JDBC Connection
```
（三次耗时 5021 / 5010 / 5015ms —— 与 `MysqlOrderProvider` 里 Hikari `connectionTimeout(5000)` 吻合。）

**机制（已读代码确认的部分）**：`MysqlOrderProvider.buildDataSource()` 从 `conn_config` 取
`passwordEnv`，用 `System.getenv(passwordEnv)` 取密码；**取不到就传空字符串**，且连接池
`setInitializationFailTimeout(-1)` 让 Hikari **跳过建池时的连接校验**，于是失败被推迟到**首次查询**才暴露成
一句泛化错误；应用**启动零告警**。

**根因（已证实）**：缺环境变量 `DS_ORDER_MYSQL_PASSWORD` → 空密码 → MySQL **认证被拒**
（`Access denied for user 'ai_ro'@'localhost' (using password: NO)`）。

#### 0.4.1 M4.0a 证据（受控复现 + 对比实验，2026-09-24）

受控条件：同一个 jar（按 `da9d5dd` 重建，`java -jar` + dev profile + 8099），唯一变量是环境变量。

| 实验 | 环境变量 | `query_orders(limit=50)` 结果 | costMs |
|---|---|---|---|
| A | **未注入**（`Test-Path Env:DS_ORDER_MYSQL_PASSWORD` = False） | `warnings:["MySQL 查询异常：Failed to obtain JDBC Connection"]`，`rows=[]`，`total=0` | 5028（SSE start→result 间隔 **5035ms**，吻合 `connectionTimeout(5000)`） |
| B | 注入正确口令 | `warnings:[]`，`rows` 4 条（NO20260327001~004，含 `PAY_TIMEOUT` / `INSUFFICIENT_BALANCE`） | 36 / 15 |

- 实验 A 的**启动日志零 WARN**：证实"启动零告警、失败推迟到首次查询"这一机制。
- 实验 A 的 SSE 里，失败与"查到了但没有数据"在证据层**长得一样**（都是 `rows=0`），只有 `warnings` 不同——
  这正是 M4 要消灭的歧义。实验 B 的第一次查询（默认时间窗）同样是 `rows=0` 但 `warnings:[]`，
  两者对照说明"0 行"有三种含义（无数据 / 窗口不对 / 连不上），必须靠权威状态区分。
- 底层原因由 M4.0b 的 WARN 直接给出（`(using password: NO)`），**不再是假设**。

#### 0.4.2 M4.0b 验证（启动期健康检查，2026-09-24）

未注入密码启动（新 jar）：

```
INFO  DataSourceHealthService - 数据源健康 dsId=ds_log_es dsType=elasticsearch costMs=150 entities=1
WARN  DataSourceHealthService - 数据源不可用 dsId=ds_order_mysql dsType=mysql costMs=5034
      原因=健康检查失败 dsId=ds_order_mysql：Failed to obtain JDBC Connection
           （根因：Access denied for user 'ai_ro'@'localhost' (using password: NO)）
      建议=未能取到连接密码：请注入环境变量 DS_ORDER_MYSQL_PASSWORD（来源 ai_data_source.conn_config.passwordEnv）
WARN  DataSourceHealthService - 数据源健康检查未全部通过：共 2 个，1 个不可用。应用继续启动（不 fail-fast）...
INFO  Application - Started Application in 12.973 seconds      ← 降级启动成立
```

注入密码启动（同 jar）：

```
INFO  DataSourceHealthService - 数据源健康 dsId=ds_log_es dsType=elasticsearch costMs=154 entities=1
INFO  DataSourceHealthService - 数据源健康 dsId=ds_order_mysql dsType=mysql costMs=44 entities=1
INFO  Application - Started Application in 8.321 seconds       ← 零 WARN
```

> 观测到的两个副作用，均接受：
> 1. `refresh()` 在启动期被调用两次（`@PostConstruct` + 配置热更新调度），故健康检查跑两轮；
>    失败场景下**主线程那轮让启动多花约 5s**（连接超时），第二轮在启动后异步发生。
> 2. 健康检查会**真正建立连接池**（`MysqlOrderProvider` 是懒建池），因此启动期不再"零连接"；
>    这是"启动即可见"的必然代价，且与 `setInitializationFailTimeout(-1)` 的取舍一致。

#### 0.4.3 M4.1 验证（端点 + 缓存 + 热更新，2026-09-24）

**① 正常态（注入密码）**——`GET /api/v1/datasource/health`：

| 调用 | `checkedAt` | `cached` | summary |
|---|---|---|---|
| 冷调用（距上次检查 > 30s） | 1790256413664 | **false** | `{total:2, healthy:2, unhealthy:0}` |
| 紧随其后 | 1790256413664（不变） | **true** | 同上 |
| `?refresh=true` | 1790256418138（**新**） | **false** | 同上 |
| 紧随其后 | 1790256418138（不变） | **true** | 同上 |

即：TTL 内命中缓存、`refresh=true` 强制重查并回写缓存，符合决策 1 A。

**② 热更新失效缓存（TTL 窗口内的对照）**：

```
预置缓存:        checkedAt=1790256522041  cached=true   （TTL 30s 未过期）
touch ai_data_source.update_time → 21:27:50.961 检测到配置变更 [上次：1789995144，当前：1790256438]
                                 → 21:27:50.968 数据源已覆盖注册 dsId=ds_log_es / ds_order_mysql
                                 → 21:27:50.998 数据源健康 dsId=ds_log_es（新 Provider 的检查结果）
热更后（未带 refresh）: checkedAt=1790256535410  cached=true   （Δ=13369ms < 30s TTL）
```

`checkedAt` 在 TTL 未过期的情况下前进到热更之后，说明旧快照被丢弃、并由重装后的 Provider 重写——
这正是 `refresh()` 里 `invalidate()` + `logReport()` 的作用。返回 `cached=true` 说明这一新值**来自缓存回写**，
而不是被本次请求临时触发重算（那会是 `cached=false`）。

**③ 不健康态（未注入密码）**——`?refresh=true` 的真实响应（节选自落盘 JSON）：

```json
{"checkedAt":1790256597649,"cached":false,
 "summary":{"total":2,"healthy":1,"unhealthy":1},
 "sources":[
  {"dsId":"ds_log_es","dsType":"elasticsearch","healthy":true,"message":null,"costMs":5,"hint":null,
   "entities":[{"entityCode":"logs","entityName":"应用日志","capability":"log_search"}]},
  {"dsId":"ds_order_mysql","dsType":"mysql","healthy":false,
   "message":"健康检查失败 dsId=ds_order_mysql：Failed to obtain JDBC Connection（根因：Access denied for user 'ai_ro'@'localhost' (using password: NO)）",
   "costMs":5012,
   "hint":"未能取到连接密码：请注入环境变量 DS_ORDER_MYSQL_PASSWORD（来源 ai_data_source.conn_config.passwordEnv）",
   "entities":[{"entityCode":"orders","entityName":"订单","capability":"entity_query"}]}]}
```

与 §4.1 的契约一致（`costMs=5012` 也与当初观测到的 5021ms 同源）；验收标准 ②③ 的 API 面达成。
原始证据文件（SSE 抓包、控制台日志、端点 JSON）在 `data/m4-evidence/`（`/data/` 已 gitignore）。

#### 0.4.4 最终验收：真实闭环 + 无回归（2026-09-24，M4 全部后端改动就位后）

注入密码启动最终 jar，发一次真实调查（`aiAgentId=4`，`sessionId=m4-final-001`）：

| 验收标准 | 结果 |
|---|---|
| **⑤ 真实闭环**：`query_orders` 返回记录 | ✅ 显式传时间窗后 `rows=4`（NO20260327001~004，含 `PAY_TIMEOUT` / `INSUFFICIENT_BALANCE`），`warnings:[]`，耗时 12ms |
| **⑥ 不回归（M3 证据契约）** | ✅ 每条订单行带 `_rawRef=order_no=...`、`query_logs` 每条带 `_rawRef=index#_id`，结果级 `rawRef` 为 `mysql://...` / `es://...`；SSE 事件类型/字段与 M3 一致 |
| **⑥ 不回归（Agent 结论质量）** | ✅ 基于真实数据给出"下单失败与限流无关（三重比对时间/用户/原因）"，不再是"数据缺失无法判定" |

> 顺带确认了"`rows=0` 有三种含义"：本次默认时间窗（最近 1 小时）查到 0 行且 `warnings:[]`，
> 与 §0.4.1 实验 A 的 0 行（有 warning）**在证据层看起来一样**——这正是 M4 用权威状态消除的歧义。

#### 0.4.5 M4.2 验证（前端接权威状态，2026-09-24）

分支 `feat/m4-2-ui-health`（从合入后的 V1 `be387bb` 切出）。

**① 构建与类型检查**：`npm run build`（`tsc -b && vite build`）→ `build exit: 0`，`✓ built in 7.81s`。

**② 纯逻辑回归**（`web/scripts/smoke.ts`，新增 18 项断言，`node scripts/.smoke.mjs` → `ALL PASS`，含原有 30 项）：

| 断言分组 | 覆盖点 |
|---|---|
| 三态→两态映射 | `healthy=true → Connected`、`healthy=false → Disconnected`、无权威状态 `→ Unknown`（**不再**把"没查过"当 Connected） |
| 入口汇总 | 有坏源即 `Disconnected`；全绿才 `Connected`；含 `Unknown` 不算全绿；空清单 `Unknown` |
| 悬停文案 | 含 `原因：`+原始异常、含 `建议：`+环境变量名、含检查耗时、保留"本次会话"辅助信息、端点不可用时明说原因 |
| 会话辅助信息 | `成功` / `返回告警`（最近一次结果的 warnings）/ `尚未查询` |
| 清单合并 | 无探查结果时清单来自端点（2 实体）；不产生重复；保留探查到的 `physicalName`（证据面板不受影响） |

**③ 前后端字段契约（对真实载荷，不是假数据）**：实时端点响应 + §0.4.3 抓取的不健康响应，逐字段核对
`checkedAt / cached / summary.{total,healthy,unhealthy}`、`sources[].{dsId,dsType,healthy,message,costMs,hint,entities}`、
`entities[].{entityCode,entityName,capability}` → 全部命中；不健康响应里 `hint` 含 `DS_ORDER_MYSQL_PASSWORD`、
`message` 含 `using password: NO`。

**④ 验收标准 ④（UI 用权威状态）**：
- 注入密码 → 端点 `healthy=2` → 面板两行均 `Connected`（绿点）；
- 未注入密码 → 端点 `ds_order_mysql healthy=false` + `hint` → 面板该行 `Disconnected`（红点）、行内直接显示 hint、悬停给出原因与建议；
- 恢复注入 → 回到 `Connected`；入口按钮在任一源不可用时显示 `N down`。

**⑤ 其它行为**：进入页面拉一次 + 每 30s 轮询（与后端缓存 TTL 同频）；面板 `Refresh` 走 `?refresh=true` 强制重查；
端点不可用时降级为 `Unknown` 并在面板顶部说明原因（旧版 `healthOf(events, dsId)` 启发式已删除）。

> 未做的检查：没有浏览器自动化（`web/package.json` 无 playwright/puppeteer），故**像素级渲染**未自动验证；
> 逻辑与契约已如上覆盖，渲染可在 `npm run dev`（5173）肉眼确认。



### 0.5 M4 直接相关的既有能力（已存在但**没有对外暴露**）

| 能力 | 位置 | 现状 |
|---|---|---|
| `IDataSourceProvider#healthCheck()` | domain SPI | 两个实现都实现了 |
| `ElasticsearchProvider.healthCheck()` | infrastructure | 直连 `GET {host}/_cluster/health`，200 → up |
| `MysqlOrderProvider.healthCheck()` | infrastructure | `SELECT 1`（**惰性建池，所以它能真实暴露连接问题**） |
| `HealthStatus` | domain 值对象 | `{healthy, message, costMs}` + `up()` / `down(msg)` |
| `IDataSourceRegistry` | domain SPI | `register/unregister/get/listByTenant`；`listByTenant(null)` 返回**全部**（源码注释明确"尚未按租户过滤"） |
| 对外端点 | trigger | **零** —— 全仓只有 `AiAgentController`、`ArmoryTestController` 两个 controller，健康检查是**死能力** |

---

## 1. 目标与验收标准

**目标**：把「数据源是否真的可用」从**查询期才暴露的泛化错误**，变成**启动可见、接口可查、UI 可显示**的一等事实。

| # | 验收标准 | 怎么测 |
|---|---|---|
| 1 | **启动即暴露**：不注入 `DS_ORDER_MYSQL_PASSWORD` 启动，日志出现**明确 WARN**，含 `dsId` 与**缺失的环境变量名**；应用仍能启动（不 fail-fast） | 清空该环境变量启动一次，检查日志 |
| 2 | **接口可查**：`GET /api/v1/datasource/health` 返回每个数据源的 `healthy / message / costMs / entities / hint` | curl 该端点，与日志、与 DB 实际状态对比 |
| 3 | **提示可操作**：`hint` 能指出"要注入哪个环境变量"（从 `conn_config.passwordEnv` 推出），而不是只回显异常 | 未注入密码时，`hint` 含 `DS_ORDER_MYSQL_PASSWORD` |
| 4 | **UI 用权威状态**：前端 Data Sources 面板显示的状态来自该端点，而非"从查询结果推断"；显示 Connected / Disconnected + 原因 | 断开订单源 → 面板显示 Disconnected 并给出 hint；恢复 → Connected |
| 5 | **真实闭环**：注入正确密码后，一次真实调查中 `query_orders` 返回记录（不再 0 命中） | 发一次 `aiAgentId=4` 且会查订单的请求，抓 SSE 确认 rows>0 |
| 6 | **不回归**：M1 四条验收 + M3 SSE 事件/rawRef 行为不变；前端现有交互（改宽/折叠/回指）不破坏 | 复用 M1/M3 回归 + `web/scripts/*` |

---

## 2. 关键决策（**先拍板**）

### 决策 1：健康检查的触发时机 —— **推荐 A**

| 选项 | 做法 | 取舍 |
|---|---|---|
| **A（推荐）** | **启动一次 + 结果缓存（TTL 30s）+ 端点支持 `?refresh=true` 强制刷新** | 启动即可见；端点不会每次都打库/打 ES；需要实时时可强制刷新 |
| B | 每次请求端点都实时检查 | 最实时，但把"健康检查"变成被高频调用的写路径（MySQL 会真连库） |
| C | 只在启动检查一次 | 最简单；但运行中数据源挂了 UI 仍显示 Connected（误导） |

### 决策 2：不健康时是否阻止启动 —— **推荐 A**

| 选项 | 做法 | 取舍 |
|---|---|---|
| **A（推荐）** | **降级启动 + WARN**，仅健康检查失败 | 与既有设计一致：`MysqlOrderProvider` 注释明确写了"业务库不可用不该让应用起不来"；`setInitializationFailTimeout(-1)` 也是同一取舍 |
| B | fail-fast（不健康就退出） | 能在部署期立刻发现配置错；但会让"某个只读源挂了"演变成"整个 Agent 服务起不来" |

> 若倾向 B，请只在**显式配置**（如 `xfg.datasource.health.fail-fast=true`）下启用，默认 A。

### 决策 3：端点放哪 —— **推荐 A**

| 选项 | 做法 | 取舍 |
|---|---|---|
| **A（推荐）** | 新建 `DataSourceHealthController`，路径 `GET /api/v1/datasource/health` | 语义清晰、与 `ArmoryTestController`（性能测试用）职责分离 |
| B | 塞进 `ArmoryTestController` | 少一个类；但那是 JMeter 性能测试端点，混入健康语义会造成误解 |

**Controller 约定**（照 `ArmoryTestController` 抄）：`@RestController` + `@RequestMapping("/api/v1/...")`
+ `@CrossOrigin(origins="*", allowedHeaders="*", methods={GET,POST,OPTIONS})` + `@Resource` 注入 + 返回 `Map<String,Object>`。

### 决策 4：UI 状态语义 —— **推荐 A**

| 选项 | 做法 | 取舍 |
|---|---|---|
| **A（推荐）** | 三态映射为两态展示：`healthy → Connected`、`!healthy → Disconnected`，并把 `message`/`hint` 作为悬停说明；另保留 `lastQueriedAt`（本会话是否查过）作为辅助信息 | 满足设计文档"显示连接状态"的要求，同时不丢"本次会话实际查询结果"这一独立事实 |
| B | 新增 `Degraded` 等第三态 | 语义更细；但设计文档只要求 Connected/Disconnected，多一态需要解释成本 |

---

## 3. 范围

**做**：
- 启动期对所有已注册 Provider 跑一次 `healthCheck()`，失败打印**可操作** WARN（含 dsId + 环境变量名）
- 新增只读端点 `GET /api/v1/datasource/health`（含缓存与强制刷新）
- 前端 Data Sources 面板改用权威状态；`hint` 在悬停/展开处展示
- 单测（domain 服务层，纯逻辑可测）+ 文档 + PR

**不做**：
- 不做告警推送（邮件/钉钉/Webhook）、不做自动重连/自愈
- 不做连接池指标（活跃连接、等待数）、不做慢查询统计
- 不做数据源**写**接口（新增/修改数据源仍走后端配置表；UI 的 Add/Manage 维持"配置入口说明"）
- 不做多租户维度的健康过滤（M5）
- 不改 SSE / 证据契约（M3 已定型）

---

## 4. 接口与数据模型

### 4.1 `GET /api/v1/datasource/health`

查询参数：`refresh=true|false`（默认 false，走缓存）。

```json
{
  "checkedAt": 1789995144000,
  "cached": false,
  "summary": { "total": 2, "healthy": 1, "unhealthy": 1 },
  "sources": [
    {
      "dsId": "ds_log_es",
      "dsType": "elasticsearch",
      "healthy": true,
      "message": null,
      "costMs": 12,
      "hint": null,
      "entities": [
        { "entityCode": "logs", "entityName": "应用日志", "capability": "log_search" }
      ]
    },
    {
      "dsId": "ds_order_mysql",
      "dsType": "mysql",
      "healthy": false,
      "message": "健康检查失败：Failed to obtain JDBC Connection",
      "costMs": 5012,
      "hint": "未能取到连接密码：请注入环境变量 DS_ORDER_MYSQL_PASSWORD（来源 ai_data_source.conn_config.passwordEnv）",
      "entities": [
        { "entityCode": "orders", "entityName": "订单", "capability": "entity_query" }
      ]
    }
  ]
}
```

> 说明：`entities` 是**逐实体**返回（每个实体一条，带它自己的 `capability`）。
> 当前种子数据每源只有 1 条（`ai_data_source_entity` 里 `ds_log_es/logs/log_search`、
> `ds_order_mysql/orders/entity_query`），所以示例里各 1 条；若某实体配置了多种能力，才会出现多条同 `entityCode` 的记录。

### 4.2 `hint` 生成规则（M4 的核心增量）

对每个不健康的数据源，按下列顺序生成**可操作**提示（取第一条命中者）：

| 条件 | hint |
|---|---|
| `dsType=mysql` 且 `conn_config.passwordEnv` 指向的环境变量**不存在** | `未能取到连接密码：请注入环境变量 <NAME>（来源 ai_data_source.conn_config.passwordEnv）` |
| `dsType=mysql` 且 `conn_config` 缺 `passwordEnv`（键不存在或为空串） | `conn_config 缺少 passwordEnv（连接密码的环境变量名）：MysqlOrderProvider 会按空密码连接，必然失败；请补成 {"passwordEnv":"<环境变量名>"}` |
| `dsType=mysql` 且 `conn_config` 缺 `jdbcUrl` | `conn_config 缺少 jdbcUrl` |
| `dsType=elasticsearch` 且 `conn_config` 缺 `host` | `conn_config 缺少 host` |
| 其它 | `null`（只保留原始 message，不编造解释） |

> 纪律：**不泄露密码本身**，只暴露环境变量名与缺失事实。

### 4.3 缓存

- 进程内单一缓存：`{checkedAt, List<SourceHealth>}`，TTL 默认 30s（可配 `xfg.datasource.health.ttl-ms`）。
- `refresh=true` 忽略缓存并回写。
- 热更新（`DataSourceProviderRegistrar.refresh()`）后**主动失效缓存**，避免显示旧 Provider 的状态。

---

## 5. 接入点：要改哪些文件

| 文件 | 改动 | 状态 |
|---|---|---|
| **新增** `domain/.../service/datasource/DataSourceHealthService.java` | 遍历 registry → `healthCheck()` → 生成 `hint` → 打印汇总 → TTL 缓存（`health(refresh)` / `invalidate()`）；纯逻辑，便于单测 | ✅ M4.0b + M4.1 |
| **新增** `domain/.../model/valobj/datasource/SourceHealth.java` | 权威视图行模型（dsId/dsType/healthy/message/costMs/hint/entities[EntityBrief]） | ✅ M4.0b |
| **新增** `trigger/.../http/DataSourceHealthController.java` | `GET /api/v1/datasource/health`（`?refresh=true` 强制重查） | ✅ M4.1 |
| `DataSourceProviderRegistrar`（infrastructure） | `refresh()` 开头 `invalidate()`、末尾 `logReport()`（热更新重装后状态即刻刷新） | ✅ M4.0b + M4.1 |
| `MysqlOrderProvider.healthCheck()` | message 带 `dsId`；**失败也记 costMs**；经 `HealthStatus.down(msg, cause)` 补上异常链根因 | ✅ M4.0b |
| `ElasticsearchProvider.healthCheck()` | 同上（HTTP 非 200 也带 `dsId`） | ✅ M4.0b |
| `HealthStatus`（domain 值对象） | 新增 `down(String message, Throwable cause)` 重载：把泛化异常还原成根因（只取异常 message，不含连接串/密码） | ✅ M4.0b（doc 原表未列，实际必需） |
| `IDataSourceRegistry`（可选） | 可新增 `List<IDataSourceProvider> listAll()` 让语义显式；当前用 `listByTenant(null)`（源码已注明返回全部） | ⏸️ 未做（非必需） |
| `app/src/main/resources/application.yml` | 新增 `xfg.datasource.health.ttl-ms`（默认 30000） | ✅ M4.1 |
| **新增** `app/src/test/.../test/m4/M4HealthServiceTest.java` | 12 个用例：hint 生成 / 不健康映射 / 缓存 TTL / refresh / invalidate / 坏 Provider 兜底（JUnit4，无 Spring 上下文） | ✅ M4.3 |
| 前端 `web/src/api/datasource.ts`（新增） | 调 health 端点：`fetchDatasourceHealth(refresh)` + `SourceHealth`/`HealthReport` 类型 | ✅ M4.2 |
| 前端 `web/src/components/DataSourceMenu.tsx` | **删除** `healthOf(events, dsId)` 启发式；改用权威状态（Connected/Disconnected/Unknown）+ 行内 hint + 多行悬停 + `Refresh`/检查时刻；清单取"探查 ∪ 端点" | ✅ M4.2 |
| 前端 `web/src/components/Workbench.tsx` | 进入页面拉一次 + 30s 轮询 + `Refresh` 走 `refresh=true`；把 `health` 下发；`globalSources = mergeHealthSources(discovered, health)`（`sessionSources` 保持探查来源，证据派生不受影响） | ✅ M4.2 |
| 前端 `web/src/components/TopBar.tsx` | 透传 health 相关 props（doc 原表未列，实际必需） | ✅ M4.2 |
| 前端 `web/src/utils/status.ts` | 三态→两态映射 `toDatasourceState` / `rollupDatasourceState` / `datasourceTipLines` / `sessionQueryNote`（纯逻辑，可被 smoke 覆盖） | ✅ M4.2 |
| 前端 `web/src/utils/evidence.ts` | 新增 `mergeHealthSources`：合并"探查到的实体"与"端点报告的已注册实体" | ✅ M4.2（doc 原表未列，实际必需） |
| 前端 `web/scripts/smoke.ts` | 新增 18 项 M4.2 断言（映射/汇总/悬停/会话辅助/合并） | ✅ M4.2 |

> **不改**：SSE / 事件契约、工具工厂、`DataSourceQueryService` 的查询语义、M3 证据派生逻辑。
> **注意**：`MysqlOrderProvider.query()` 的告警文案（`MySQL 查询异常：...`）**保持原样**——它是 M3 证据层
> 已在用的契约文案，健康可见化不通过改它来实现。

---

## 6. 落地顺序（每步独立可验证）

| 阶段 | 内容 | 验收 | 状态 |
|---|---|---|---|
| **M4.0a** | **确认根因**（必须先做）：清空 `DS_ORDER_MYSQL_PASSWORD` 启动，复现 `Failed to obtain JDBC Connection`；确认空密码是否就是原因（可对比注入正确值后的行为）。顺带把本文档纳入 git（见 §0.2 分支依赖） | 能一句话说清根因且有证据（日志 + 对比实验） | ✅ 完成，证据见 §0.4.1 |
| **M4.0b** | 启动期健康检查 + 明确 WARN（`DataSourceHealthService` 最小版 + Registrar 接线），**暂不对外暴露端点** | 未注入密码启动 → 日志出现含 `DS_ORDER_MYSQL_PASSWORD` 的 WARN；注入后 → 无 WARN | ✅ 完成，证据见 §0.4.2 |
| **M4.1** | 健康端点 + 缓存 + `?refresh=true` + 热更新失效缓存 | curl 端点：2 个源、状态与日志一致；`refresh=true` 生效；改配置触发热更后状态刷新 | ✅ 完成，证据见 §0.4.3 |
| **M4.2** | 前端接权威状态（依赖 UI 重构已合入，见 §0.2） | Data Sources 面板与端点一致；未注入密码时显示 Disconnected + hint | ✅ 完成，证据见 §0.4.5 |
| **M4.3** | 单测（`DataSourceHealthService` 的 hint 生成 / 缓存 TTL / 不健康映射）+ 文档更新 + PR | 单测通过（JUnit4 `JUnitCore` 直跑）；PR 合入 V1 | ✅ 单测 14/14（其中 2 例为收尾小修补的 hint 空档）；后端 PR #11、前端 PR #12 均已合入 |

> 建议 M4.0a/M4.0b 合成一个小 PR 先合（纯后端、零对外行为变更），再单独做 M4.1/M4.2。

### 6.1 M4 实现要点（供 M4.2/M4.3 接手）

- `DataSourceHealthService` 接口面：`health(boolean refresh)`（端点入口，带 TTL 缓存 + 双检锁防击穿）、
  `checkAll()`（真实重查、不走缓存，单测直接用它）、`invalidate()`（热更新失效）、`logReport()`（真实重查 + WARN + 回写缓存）。
  缓存 TTL 由 `xfg.datasource.health.ttl-ms` 注入（字段默认 30s，脱离 Spring 也能用）。
- `hintOf(dsType, config)` 严格按 §4.2 的规则、**取第一条命中者**，其它情况返回 `null`。
  ~~已识别的规则空档：`conn_config` 里 `passwordEnv` 本身缺失/为空时只回 `null`~~
  → **空档已补齐**（收尾小修，⑪）：缺键/空串时给出 `conn_config 缺少 passwordEnv…`，
  与"变量未注入"区分开（前者是配置缺键，后者是环境缺变量），并有 2 个单测钉住。
- `entities` 来自 `provider.listEntities()`，是**逐实体**（当前种子数据每源 1 条：`logs/log_search`、
  `orders/entity_query`）；§4.1 的示例已按此修订，并补了"为什么可能同 `entityCode` 多条"的说明。
- 健康检查会建立真实连接（MySQL 建池、ES 发 HTTP），因此**不要**把它接进任何高 QPS 路径；
  端点默认走缓存正是为此。

### 6.2 手工跑单测（JUnitCore + 手工 classpath；T1 修好后一般不再需要）

测试类：`ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/m4/M4HealthServiceTest.java`
（JUnit4，不依赖 Spring 上下文/DB/网络：Provider 与配置仓储都是手写桩）。

```powershell
# 1) 编译（离线）
$env:JAVA_HOME='D:\develop\IntelliJ IDEA 2025.3.2\jbr'
mvn -o -B -DskipTests test-compile

# 2) 手工 classpath + JUnitCore 直跑（仓库路径按本机实际调整）
$repo='D:\develop\apache-maven-3.9.12-bin\apache-maven-3.9.12\mvn_repo'
$cp = @(
 'ai-agent-station-study-app\target\test-classes',
 'ai-agent-station-study-domain\target\classes',
 "$repo\junit\junit\4.13.2\junit-4.13.2.jar",
 "$repo\org\hamcrest\hamcrest-core\1.3\hamcrest-core-1.3.jar",
 "$repo\com\alibaba\fastjson\2.0.28\fastjson-2.0.28.jar",
 "$repo\com\alibaba\fastjson2\fastjson2\2.0.28\fastjson2-2.0.28.jar",
 "$repo\com\alibaba\fastjson2\fastjson2-extension\2.0.28\fastjson2-extension-2.0.28.jar",
 "$repo\org\slf4j\slf4j-api\2.0.16\slf4j-api-2.0.16.jar",
 "$repo\ch\qos\logback\logback-classic\1.5.16\logback-classic-1.5.16.jar",
 "$repo\ch\qos\logback\logback-core\1.5.16\logback-core-1.5.16.jar"
) -join ';'
java -cp $cp org.junit.runner.JUnitCore cn.bugstack.ai.test.m4.M4HealthServiceTest
```

> 坑（沿用 M1/M3）：`com.alibaba:fastjson:2.0.28` 是 **fastjson1 兼容层**，必须同时带 `fastjson2` 与
> `fastjson2-extension`；**环境变量存在与否**这条分支要求测试进程里确实有 `JAVA_HOME`（或 `PATH`）——
> 用 `-D` / 环境变量启动 `java` 时自然满足。

**结果（2026-09-24）**：`OK (12 tests)`。12 个用例覆盖：§4.2 四条 hint 规则中的三条命中路径 + "无据可依返回 null"、
健康/不健康映射（message/hint/costMs/entities）、Provider 抛异常的兜底、costMs 本地补测、
缓存命中（TTL 内不重复打数据源）、`refresh=true` 绕过并回写、`invalidate()` 强制重查、TTL 过期重查、
`logReport()` 回写缓存 + 空注册表不抛异常、`checkAll()` 按 dsId 排序。

**TTL 注入的端到端验证**（当时 CLI 参数还无效，故用系统属性覆盖）：

```
java -Dxfg.datasource.health.ttl-ms=5000 -jar ai-agent-station-study-app.jar
call 1 (cold)    : checkedAt=1790257038280 cached=false
call 2 (t+1s)    : checkedAt=1790257038280 cached=true      ← 5s 内命中
call 3 (t+7s)    : checkedAt=1790257045434 cached=false     ← 过期重查（证明 TTL 真被读成 5s）
call 4 (t+8s)    : checkedAt=1790257045434 cached=true
```

即 `@Value` 方法注入（`setTtlMs`）生效；默认值仍为 `application.yml` 的 30000。

### 6.3 测试工具链（T1：已完成）

**问题**：`app/pom.xml` 把 surefire 钉在 `2.6` 且硬编码 `<skipTests>true</skipTests>`（`-DskipTests=false` 覆盖不了），
导致 `mvn test` 永远不跑测试——每个里程碑都只能手搓 classpath 用 `JUnitCore` 验证。

**改法**（3 处，都在构建配置里，零业务代码改动）：

| 位置 | 改动 |
|---|---|
| `app/pom.xml` surefire | 版本 `2.6` → **`2.22.2`**（离线仓库只有 `surefire-junit4:2.22.2`）；**删除** `<skipTests>true</skipTests>`（改由命令行 `-DskipTests` 控制，打包配方不受影响） |
| `app/pom.xml` surefire | **显式声明 `surefire-junit4:2.22.2` provider**：classpath 上同时有 junit4 与 junit-jupiter，2.22.2 的自动选择可能挑到 `surefire-junit-platform`（离线仓库只有 3.5.2，2.22.2 缺失）→ 显式声明避免"provider not found" |
| `pom.xml`（根）surefire | `<failIfNoSpecifiedTests>false</failIfNoSpecifiedTests>`：本项目测试都在 app 模块，否则 `mvn test -Dtest=Xxx` 会在没有测试的模块上以 "No tests matching pattern" 直接中断 reactor |

**测试分类**（**二开清理后：27 个测试类**，按"是否需要外部服务"分）：

| 类别 | 数量 | 内容 | `mvn test` 默认 |
|---|---|---|---|
| **单元**（无外部依赖） | **10 类 / 80 用例** | `armory/BeanRegistryConcurrencyTest`(1)、`armory/RagAnswerAdvisorTest`(3，桩化 VectorStore)、`m3/M3RawRefStripTest`(2)、`m3/M3ToolCallEventTest`(3)、`m4/M4HealthServiceTest`(16)、`p0/ConfigFingerprintTest`(7，T2)、`p1/EsMcpToolExposureTest`(6)、`p1/EsQueryGuardTest`(13)、`p1/P1CrossSourceCorrelationTest`(17)、`p1/SqlTemplateRendererTest`(12) | ✅ 跑 |
| **集成**（需 MySQL / ES / pgvector / LLM Key / 完整 Spring 上下文） | 17 类 | `test/dao/**`、`test/spring/**`、`test/performance/**`、`m3/M3OrderFilterTest`（真实 MySQL + `DS_ORDER_MYSQL_PASSWORD`）、`m3/M3RawRefProviderTest`（真实 ES + MySQL） | ⛔ 排除（在 `app/pom.xml` 的 `<excludes>` 里） |

> **为什么不改名成 `*IT`**：Maven 的 `*IT` 约定由 **failsafe** 插件执行，而离线仓库里**没有 `maven-failsafe-plugin`**——
> 改名的结果是这些测试**连 maven 都跑不了**。现在的做法（默认排除 + `-Dtest=` 可覆盖）能同时满足
> "`mvn test` 不红"与"集成测试仍可按需单跑"，且不动其余测试文件名。

**怎么用**：

```powershell
$env:JAVA_HOME='D:\develop\IntelliJ IDEA 2025.3.2\jbr'

mvn -o -B test                      # 单元测试（离线、无需任何外部服务）
mvn -o -B test -DskipTests          # 跳过测试（打包配方沿用这个）
mvn -o -B test -Dtest=AiClientDaoTest   # 按需单跑被排除的集成测试（需 MySQL；受限沙箱下还需放宽）
```

**验证（2026-09-25，含后续 T2 与收尾小修的新增用例）**：

| 命令 | 结果 |
|---|---|
| `mvn -o -B test` | ✅ `Tests run: 30, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`（T1 时 21 → T2 后 28 → 收尾小修后 30） |
| `mvn -o -B test -DskipTests` | ✅ 跳过测试，`BUILD SUCCESS`（打包配方不受影响） |
| `mvn -o -B test -Dtest=AiClientDaoTest` | ⚠️ 受限沙箱下 `@SpringBootTest` 起不来，见下 |

> **⚠️ 集成测试在受限沙箱里跑不了（环境限制，非代码问题）**：`@SpringBootTest` 上下文加载时 Mockito 需要
> self-attach 到当前 JVM，`workspace-write` 沙箱下报 `Could not self-attach to current VM using external process`
> → `MockitoInitializationException`。**放宽到 `danger-full-access` 后可以正常跑**（T2 期间已验证：
> `mvn -o test -Dtest=AiClientConfigRevisionDaoTest` → 3 用例全绿，36.8s）。
> 顺带一个交叉印证：受限沙箱下那次上下文启动的日志里出现了 M4.0b 的 WARN——
> `数据源不可用 dsId=ds_order_mysql … 建议=未能取到连接密码：请注入环境变量 DS_ORDER_MYSQL_PASSWORD`
> （因为 maven 进程环境里没有该变量），说明启动期探活在真实 Spring 上下文里同样生效。

### 6.4 热更新删除检测（T2：已完成）

**原始 bug**（部署文档 §四.3）：变更检测只看 `MAX(update_time)`，且调度器用 `>` 比较：

```
删掉"最新那一行"   → MAX 变小 → current < last → `>` 为假 → 静默不重载
删掉"任意非最新行" → MAX 完全不变 → 同上
```

改配置却要等下一次真正的变更才生效，**且没有任何日志**——这是 P0。

**根因澄清（实测）**：软删其实**已经**能被检测——12 张配置表 + `ai_agent_flow_config` 的 `update_time`
在真实库里都是 `on update CURRENT_TIMESTAMP`（`information_schema` 实测 15 张表全部如此），
所以 `UPDATE ... SET status=0` 会自动刷新 `update_time`、版本号变大。**真正的漏洞只有硬 `DELETE`**。

**改法**：把"版本号"升级为**配置指纹**，并改用**等值比较**：

| 组件 | 作用 |
|---|---|
| `MAX(update_time)`（现有 `queryConfigVersion()`，12 张表 UNION） | 负责"**改**"：任何 UPDATE（含软删）都会让它变大 |
| **总行数**（新增 `queryConfigRowTotal()`，同样 12 张表 `SUM(COUNT(*))`） | 负责"**增/删**"：删除不一定会动 MAX，但一定会动行数 |
| `ai_agent_flow_config` 的 `MAX(update_time)` | 阶段提示词单独一路（保留原有容错：缺列不影响其它表） |
| 调度器比较方式 `>` → `!=` | 版本**回退**（有人删了配置）也必须算变更；热更新幂等，误触发代价远小于漏更新 |

指纹形如 `1790256530:104:1789810563`，直接打在日志里，便于对账。

**运维约定**：配置行**不要 `DELETE`**（行数变化虽能被指纹捕捉，但版本号会回退、审计线断裂）；
停用配置请软删 `UPDATE ... SET status = 0`，`update_time` 会自动刷新。

**验证（2026-09-25）**：

① 纯逻辑单测 `test/p0/ConfigFingerprintTest`（7 例，`mvn -o test` 默认跑）：钉住"删行（版本不变）必须改变指纹"、
"版本回退必须改变指纹"、"软删必须改变指纹"、"null（缺列）不产生指纹抖动"。

② 集成回归 `test/dao/AiClientConfigRevisionDaoTest`（3 例，`danger-full-access` 下 36.8s 全绿）：
往 `ai_data_source_entity` 插入 `ds_id='__p0_t2_probe__'` 的**探针行**（所有读取都按真实 dsId 过滤，对运行时不可见），
`@Before/@After` 双清理；断言软删使版本号变大、硬删使版本号回退而指纹仍变化。

③ SQL 层真实数字（事务内执行 + `ROLLBACK`，0 残留）：

| 场景 | `version` | `rows_total` | 旧逻辑（`>` 看 version） | 新逻辑（等值看指纹） |
|---|---|---|---|---|
| 1 基线 | 1790256530 | 104 | — | — |
| 2 插入探针 | 1790340396 | 105 | ✅ 检测到 | ✅ 检测到 |
| 3 软删（与上一步**同一秒**） | 1790340396 | 105 | ❌ 漏 | ❌ 漏（见下"已知极限"） |
| 4 硬删（删掉刚插入的最新行） | **1790256530（回退）** | 104 | ❌ **漏**（版本变小） | ✅ 检测到（靠行数） |

④ 端到端（真实应用 + 真实 MySQL，`java -jar` 起服务后操作数据库）：

```
20:49:47 检测到配置变更 [上次：1790256530:104:1789810563，当前：1790340584:105:1789810563]  ← 插入探针
20:50:51 检测到配置变更 [上次：1790340584:105:1789810563，当前：1790256530:104:1789810563]  ← 删除探针：版本号回退
                                                                             旧逻辑会静默跳过，新逻辑检测到 ✅
```

**已知极限（如实记录，未修）**：`update_time` 是**秒级精度**，所以"应用已经观察到某次变更之后、
同一秒内再改一次"可能因版本号与行数都没变而被漏掉（上表场景 3）。触发条件是亚秒级竞态，很窄，
且下一次真实变更会一并带出正确状态。若将来要彻底消除，需把 `update_time` 提到毫秒精度
（`datetime(3)` + `ON UPDATE CURRENT_TIMESTAMP(3)`；注意 `UNIX_TIMESTAMP()` 会丢毫秒部分）
或改成库侧审计表/触发器。

### 6.5 收尾小修（④/⑪/⑬，分支 `chore/main-args-hint-docs`）

| 项 | 内容 | 验证 |
|---|---|---|
| **④** `Application.main` 传 `args` | 原来 `SpringApplication.run(Application.class)` 丢掉 args，CLI 参数被静默忽略 | ✅ 打包含改动的 jar 后 `java -jar … --server.port=8098` → 日志 `Tomcat started on port 8098`，**8099 未监听**（改前该参数无效）；健康端点经 8098 正常返回 `total:2, healthy:2`。默认（不带参数）仍绑 8099 |
| **⑪** hint 规则空档补齐 | `conn_config` 缺 `passwordEnv` 键/空串时，原逻辑回 `null`；现给出 `conn_config 缺少 passwordEnv…`，与"变量未注入"区分开 | ✅ `M4HealthServiceTest` 新增 2 例（缺键 / 空串） |
| **⑬** 文档修订 | §4.1 `entities` 示例改为与种子数据一致（每源 1 条）+ 说明"何时会同 entityCode 多条"；§0.1 登记 PR #11/#12/#13；§4.2 规则表补新规则；§6.1 空档标记为已补齐 | — |

单测总数：**30**（`mvn -o -B test` → `Tests run: 30, Failures: 0, Errors: 0, Skipped: 0`）。


---

## 7. 已知边界（不在 M4 范围）

- 告警推送、自愈重连、连接池指标 —— 不做。
- 数据源写接口（新增/编辑）—— 仍走 `ai_data_source` 等 4 张配置表。
- 多租户维度的健康隔离 —— M5（`listByTenant` 目前返回全部，源码已注明）。
- 前端 Metrics / Trace / K8s 查看器 —— 需先有对应数据源（见 §8 第 5 项）。
- 本轮已观测到的 **MySQL 真实可用性**只在注入环境变量后成立；**生产部署必须把该变量纳入部署清单**（建议写进 `docs/dev-ops/UPGRADE-*.md`）。

---

## 8. 附：M4 之后的任务清单（简略）

按「先地基、后能力」排；括号内是主要依据/入口。

| 优先级 | 任务 | 说明 | 依据入口 |
|---|---|---|---|
| **P0** | **T1 测试工具链修复** ✅ **已完成**（PR #13） | `mvn test` 已能跑：surefire `2.6`→`2.22.2` + 显式钉 `surefire-junit4` provider + 去掉硬编码 `skipTests=true` + 根 pom 关 `failIfNoSpecifiedTests`；35 个测试类按"需不需要外部服务"分类（6 类 30 用例默认跑，30 类集成默认排除但可 `-Dtest=` 单跑）。为什么不改名 `*IT`：离线仓库无 `maven-failsafe-plugin`，改名后连 maven 都跑不了。详见 §6.3 | `app/pom.xml`、根 `pom.xml` |
| **P0** | **T2 热更新检测不到"删除/软删"** ✅ **已完成**（PR #13） | 实测澄清：软删**本来就能**检测（配置表 `update_time` 都是 `ON UPDATE CURRENT_TIMESTAMP`），真正的漏洞只有硬 `DELETE`。改法：把"版本号"升级为**配置指纹** `版本:行数:提示词版本`（新增 `queryConfigRowTotal()` 12 张表行数）并改用**等值比较**（版本回退也算变更）。已验证：单测 7 例 + 集成回归 3 例 + SQL 真实数字 + 端到端（删探针行时"版本号回退"仍被检测到）。已知极限：`update_time` 秒级精度下"同秒内二次变更"可能漏。详见 §6.4 | `AiClientConfigRefreshScheduler`、`ai_client_config_mapper.xml`、部署文档 §四.3 |
| **P1** | **前端双轴时间线视图** 🔄 **PR 待创建**（分支 `feat/p1-3-timeline-view`） | 后端按桶对齐的数据在前端画成泳道+贯穿竖线+高亮桶（不再是逐行读表），补齐 P1 文档 §7.6 的边界；同时新增 `web/scripts/render-check.tsx`（SSR 渲染断言）。详见 `docs/dev-ops/P1-3-timeline-view-design.md` | P1 §7.6、`web/src/components/evidence/TimelineView.tsx` |
| **P1** | **多轮会话记录丢失修复** 🔄 **同分支待评审** | 同一会话问第二个问题会 `events: []` 清空 + `question` 覆盖 → 改为追加式 `turns`；派生化分层（证据/时间线看全会话，结论/诊断/耗时只看当前轮），Action 带 `turn` 防串轮。根因 100% 在前端，后端零改动。详见 M3 文档 §8 | `M3-evidence-panel-ui-design.md` §8、`web/src/store/useSessions.ts` |
| **P3** | **收尾小修（④/⑪/⑬）** ✅ **已完成**（分支 `chore/main-args-hint-docs`） | ④ `Application.main` 传 `args`（CLI 参数曾被静默忽略）；⑪ hint 规则空档补齐（缺 `passwordEnv` 键/空串）；⑬ 文档修订（§4.1 示例、§0.1 PR 登记、§4.2 规则表）。详见 §6.5 | `Application.java`、`DataSourceHealthService.hintOf` |
| **P1** | **跨源关联**（日志时间线 ↔ 订单时间线对齐） ✅ **已完成**（分支 `feat/p1-cross-source-correlation`） | 实现方式：新增 `cross_source` 能力与工具 `query_correlated_timeline`，由 `CrossSourceCorrelationService` 按**能力**发现两侧源、各查各的、按时间桶对齐（**不改 Provider SPI**）；结果仍是 `QueryResult`，于是 M3 的证据注册表/rawRef/模型版剥离全部复用。真实端到端已验证：`2026-03-27 10:30` 桶同时含限流 WARN 与 `PAY_TIMEOUT` 订单，桶级引用挂两侧原文。详见 `docs/dev-ops/P1-cross-source-correlation-design.md` | M1 §10"留待后续单独做" |
| **P1** | **第三个数据源（PostgreSQL 支付流水）** ✅ **已完成**（分支 `feat/p1-2-postgres-third-source`） | 新 `ds_type=postgres` + `PostgresEntityProvider`（注册器只加一个 `case`），Agent 侧零改动即产出 `query_payments`；顺带把 M1 预留的 `EntityQuery.filters`/`FieldFilter` 接线成**字段字典驱动的通用过滤**（模板加 `${filters}` 占位符才开放，零回归）。真实端到端把 `PAY_TIMEOUT` 追到"微信网关 30012ms 超时"。详见 `docs/dev-ops/P1-2-postgres-third-source-design.md` | M1 §10、M4 §7"需先有对应数据源"（注意：**Metrics/Trace 查看器仍需指标源**，PG 不解锁它） |
| **P1** | **ES MCP 暴露 6 个写/删类工具** | 部署文档 §四.2：`create_index`/`create_mapping`/`bulk`/`reindex`/`create_index_template`/`delete_index_template`，默认 ES 无安全特性、无第二道防线，目前仅靠 Prompt 约束 | 部署文档 §四.2；`ai_client_tool_mcp` 配置 |
| **P2** | **M5 多租户** | `tenant_id` 已埋点（`ai_data_source.tenant_id`），但 `DataSourceQueryService` 注死 `DEFAULT_TENANT_ID="default"`、`listByTenant` 返回全部。全链路隔离（含前端）是大工程 | M1 §10、`DataSourceQueryService` |
| **P2** | **M6 会话级版本快照** | 解决部署文档 §四.4「整体装配非原子」（热更新逐个替换 Api→ToolMcp→Model→Advisor→Client，进行中会话可能拿到"新 MCP + 旧 ChatClient"混合体）；顺带承担会话持久化 | 部署文档 §四.4 |
| **P2** | **健康端点暴露面收敛** | `GET /api/v1/datasource/health` 当前无鉴权、`@CrossOrigin(origins="*")`，会暴露 dsId / 根因 / hint（**不含密码**，纪律已守）。生产部署前需评估 CORS 收敛或加鉴权 | `DataSourceHealthController` |
| **P3** | **T2 已知极限：`update_time` 秒级精度** | 同秒内二次变更可能漏检（亚秒级竞态）。彻底解决需配置表迁 `datetime(3)` + `ON UPDATE CURRENT_TIMESTAMP(3)`（12+ 张表）或库侧审计表/触发器 | §6.4"已知极限" |
| **P3** | **前端渲染级回归缺失** | 🔶 **部分补齐（P1-3）**：新增 `web/scripts/render-check.tsx` —— 用 `react-dom/server` 静态渲染真组件并断言渲染结构（泳道/标记/高亮/刻度等 11 项），补上"只验纯逻辑不验渲染"的缺口；**仍无** playwright/puppeteer，像素级与交互（点选/悬停）自动化未做 | `web/scripts/render-check.tsx`、`docs/dev-ops/P1-3-timeline-view-design.md` |
| **P3** | **字段字典驱动工具 schema** | M1 §7 设计为"schema 由 `ai_data_source_field` 生成"，实际是手写 schema（`orderNo` 是 M3.2 手工加的） | M1 §7、`DataSourceToolCallbackFactory` |
| **P3** | **前端收尾** | ① 配 `web/.env`（`VITE_KIBANA_BASE`，否则回指是"未配置"降级）；② antd 按需引入（当前 bundle 565KB）；③ UI 细节打磨（KPI/密度/空态） | `web/.env.example`、UI 设计文档 |
| **P3** | **分支与仓库清理**（已与用户约定"留到最后"） | ① `main` 与 `origin/main` 分叉（本地独有 75 / 远端独有 1）；② 4 个已合并远端特性分支（`feat/m1-unified-datasource`、`feat/m1-3-mysql-order-provider`、`feat/m1-4-finalize`、以及合并后应删的 `feat/m3-evidence-panel`）；③ 15 个本地历史分支（13 个 `3-x-*` + `V1-secondary-dev-old-history` + `main`）——**删远端已合并分支不会丢提交**，但 `3-x-*` 承载上游课程历史，删了不可恢复 | 上一会话的分支审计结论 |

---

## 附：本文档写作时的关键事实来源

- `HealthStatus`：`domain/.../model/valobj/datasource/HealthStatus.java`（`{healthy,message,costMs}`）
- 两个 `healthCheck()` 实现：`ElasticsearchProvider`（`_cluster/health`）、`MysqlOrderProvider`（`SELECT 1`）
- `listByTenant(null)` 返回全部：`DataSourceRegistry` 源码注释
- MySQL 失败机制：`MysqlOrderProvider.buildDataSource()`（`passwordEnv` → `System.getenv`）+ `setInitializationFailTimeout(-1)`
- 症状证据：M4 前一会话的真实 SSE 抓包（3 次 `query_orders` 均 5010~5021ms 且 `Failed to obtain JDBC Connection`）
- controller 约定：`trigger/.../http/ArmoryTestController.java`
