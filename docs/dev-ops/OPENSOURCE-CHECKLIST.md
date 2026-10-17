# 开源前隐私与凭据检查清单（Troubleshooting-Agent）

> 适用场景：本仓库是上游课程项目「ai-agent-station」的二次开发版，**计划开源**。
> 本文是**开源前的唯一操作清单**：先看 §1 结论，再按 §3 执行，最后用 §4 的脚本做终检。

---

## 1. 结论（2026-10-16 体检）

| 项 | 状态 |
|---|---|
| 当前工作树（tip） | ✅ 已清干净（真实 key 注释、演示口令、中转域名、pom 个人信息都已占位化，见 PR `chore(privacy)`） |
| **git 历史** | ⛔ **含上游作者留下的真实密钥**，命中 16 个提交；**因此不能直接把这个仓库转公开** |
| 处置方案 | ✅ 已定：**快照式开源**（新建公开仓库，只放工作树、不带历史）+ 凭据归属方轮换/吊销 |

### 1.1 历史里的凭据清单（**只记形态与提交，不记任何 key 片段**）

> ⚠️ **本文档会被公开**（它随开源快照一起发布），所以这里**不写任何 key 的片段**——连前 6 位都不写。
> 需要定位具体 key 时，在私有仓库里按"引入提交 + 文件路径"查即可。

| # | 形态 | 出现位置（历史） | 引入者 | 命中提交数 | 谁能轮换 |
|---|---|---|---|---|---|
| K1–K5 | 5 个 OpenAI 兼容 `sk-…` key（35 字符级） | `application-dev.yml` / `application-test.yml`（老版本）、`AiAgentTest`、`AiSearchMCPTest`、`AiAgentMCPESTest`、`AiAgentStepTest`、`DynamicRateLimitQueryTest` | 上游课程作者（2025-06~08 课程提交） | 4 | **上游作者**（非本项目可操作） |
| K6 | 百度千帆 API Key（`bce-v3/…` 形态） | 上述测试类 + 建库基线 `ai_client_tool_mcp` 5006 行 | 上游课程作者（2025-10） | 16 | 上游作者 |
| K7 | 高德开放平台 Web 服务 key（32 位 hex） | 建库基线 `ai_client_tool_mcp` 5005 行 + `docs/dev-ops/sql-bak/*.sql` | 上游课程作者（2025-10-05，被导入提交 `455725e` 一并带入） | 16 | 上游作者 |
| K8 | SSE apikey（15 字符，代码注释形态） | `AiClientToolMcpNode` 注释 | 上游课程作者（2025-07-06） | 2 | 上游作者；**已由 PR `chore(privacy)` 从工作树移除** |

> **重要**：`sk-…` 这几个 key 属于**上游课程作者的演示环境**，不是本项目的 key。
> 本项目自己的对话 key 只存在于**本机数据库** `ai_client_api`（`switch-to-deepseek.sql` 里是占位 `sk-你的deepseek-api-key`），**从未进过仓库**——已核实（工作树 / 全历史 / 本机未跟踪目录均 0 命中）。

> **写文档的纪律（写给未来的自己）**：引用泄露凭据时**只写形态 + 引入提交 + 文件路径**。
> 本次就踩过一次：本文件初版把 K7/K8 的**完整值**抄了进来，被 `scan-secrets.ps1` 抓出后才打码——
> 检查脚本因此新增了「32 位 hex 独立 token」与「`apikey=…`」两条规则。

### 1.2 为什么"轮换密钥"这一步不能由 AI/本仓库代做

- 轮换必须在**百度智能云 / 高德开放平台控制台**用账号 + 短信/2FA 操作，属于账号持有人的动作；
- 这些 key 的持有人是**上游作者**，我们连控制台入口都没有；
- 我们这边能做的、也已做的：**不把历史推给公众**（快照式开源）→ 公网不再有第二份拷贝。

### 1.3 因此，真正要做的两件事（就这两件）

1. **不发布历史**（快照式开源，§3）——完成这一步后，**公网上就不存在这些 key**（我们这份是全新仓库，从来没有过）。
2. **本项目不再使用这些凭据**（已做）：建库基线已改为 `https://api.deepseek.com` + 占位 key；课程遗留的 MCP 行（含高德/百度那两条）由 PR `chore(cleanup)` 删除。

> **可选、且对项目没有实际作用**：通知上游作者或向平台提泄露工单。
> 它**不会改善本项目的任何东西**（我们的公网暴露在 §1 第 1 步做完后就归零了），只对"那些 key 的持有人"有意义。
> 唯一的例外：如果某个 key **确实是你本人的**，那你必须自己去控制台吊销重建——这时它才是"必做"而不是"可选"。

### 1.4 为什么不能"直接移除历史里的 key"

"直接移除"这件事**已经做了，而且只在能做的那一层生效**：

| 层 | 能否移除 | 现状 |
|---|---|---|
| 当前工作树 / 新提交 | ✅ 能，`git rm` + 改行即可 | 已做：K8（15 字符注释 token）由 PR `chore(privacy)` 删除；高德/百度两条 MCP 行与 5 个 `sql-bak/*.sql` 由 PR `chore(cleanup)` 删除 |
| **已提交的历史对象** | ❌ **删不掉**（git 对象内容寻址、不可变） | 只能"重写历史"或"不发布这段历史"，二选一 |
| 私有仓库的 `.git` 对象库 | ⚠️ 可重写，但有残留 | 见下 |

**"重写历史"（filter-repo/BFG）的真实代价与残留窗口**——这就是我们没选它的原因：

1. 所有 commit hash 变化 → 远端 16 个分支都要**强推**，协作者必须重新克隆；
2. **强推不会立刻抹掉旧对象**：旧 commit 只要知道 SHA 仍可在一段时间内被访问，GitHub 侧要到内部 GC（或联系 GitHub 支持做 purge）才真正消失；
3. 任何 **fork / 本地克隆 / CI 缓存 / PR 页面里的旧 diff** 都可能留着副本，我们无法逐一控制；
4. 本机离线环境未必有 `git-filter-repo`，要联网装。

而**快照式开源没有这些窗口**：新仓库、单条无父提交，公网上从第一天起就不存在这些字符串。
所以结论是：**"直接移除"= 当前树移除（已做）+ 不发布历史**；想把历史也洗干净，只有重写这一条路，且它**严格弱于**快照。

> 可测量的分界线（merge 完 cleanup 后自己验一次）：
> `powershell -NoProfile -File docs/dev-ops/scripts/scan-secrets.ps1 -TreeOnly` → **0 命中**（工作树已彻底移除）
> `powershell -NoProfile -File docs/dev-ops/scripts/scan-secrets.ps1` → 仅历史命中、退出码 2（历史仍留着，故不发布历史）

---

## 2. 需要人工执行的动作

### 2.1 如果你本人拥有其中任何一个 key（自查）

若某个 `sk-…` 其实是你自己在用的（例如某个第三方中转服务的 key），请立即在对应服务商控制台**吊销并重建**，然后更新本机数据库
`ai_client_api.api_key`（**只改库，不要写回仓库**）。

### 2.2 上游密钥：**本项目无需处理**（留档备查）

这些 key 的持有人是上游作者，**我们既不能轮换、也不欠他一次通报**——本项目的暴露已在 §3 快照后归零。
下面这段只在你想顺手告知时用（**可选，对项目零影响**）：

> 我在把基于课程项目「ai-agent-station」的二次开发版开源前做了凭据扫描，发现课程历史里含若干真实密钥
> （`81aee96`/`60968de`/`be3ded5`/`ecd6d03` 的 OpenAI 兼容 `sk-…`、`bcbe8a8` 的高德 key、`d839b1d`/`156a7cb` 等的百度千帆 `ALTAK-…`），
> 建议尽快在控制台吊销/重置。我的二次开发仓库不会发布这段历史。

若真要强制失效：高德开放平台「应用管理」删除/重置该 key、百度智能云千帆「API Key 管理」删除该 AK
（**仅当 key 属于你本人时才是必做动作**）。

### 2.3 本项目自己的凭据（务必自查一次）

- 本机数据库里的 DeepSeek key：**不要**把 `mysqldump` 提交进仓库（历史教训：课程仓库的 `docs/dev-ops/sql-bak/*.sql` 就是这么泄的，本仓库已删除该目录）；
- 若将来要加 CI/部署：凭据只放 `GitHub Secrets` / 环境变量，仓库里只留 `.env.example` 与占位符。

---

## 3. 快照式开源（推荐执行顺序）

> **前提**：三个 PR 已合并（`chore(secondary-dev-cleanup)` → `fix/privacy-hygiene` → `docs(p2)-feishu`），且本地 V1 已与远端同步。
> 脚本：`docs/dev-ops/scripts/opensource-snapshot.ps1`（默认已按"排除内部文档"的结论配置，见 §3.2）。

### 3.1 一条命令搞定（推荐）

```powershell
# 1) 同步
git -c http.proxy=socks5h://127.0.0.1:7897 fetch origin
git checkout V1-for-secondary-development
git merge --ff-only origin/V1-for-secondary-development

# 2) 生成快照分支（不带历史；自动排除 §3.2 的文档；生成前会跑一次凭据终检，发现即中止）
powershell -NoProfile -File docs/dev-ops/scripts/opensource-snapshot.ps1 -Commit

# 3) 推到新的公开仓库（仓库需你先在 GitHub 上创建，空仓库即可）
git remote add public https://github.com/<你的账号>/<公开仓库名>.git
git push public public-snapshot:main
```

> **脚本与环境**：脚本刻意写成 **纯 ASCII + Windows PowerShell 5.1 兼容**（本机没有 `pwsh`；
> 用 UTF-8 无 BOM 的中文 `.ps1` 在 5.1 下会被按 ANSI 读而报语法错）。PowerShell 7 下同样可跑。
> 另一个必须记住的点：**快照务必在 `chore(secondary-dev-cleanup)` 合并之后取** ——
> 在它合并前，工作树里仍会有 6 处高德 key（`docs/dev-ops/sql-bak/*.sql` 5 个文件 + 建库基线 5005 行），
> 这些正是 cleanup PR 删除的内容；扫描器会因此以退出码 1 拦下快照（这是**有意设计**）。

### 3.2 默认排除清单（不随开源发布；如需发布加 `-IncludeInternalDocs`）

| 文件 | 为什么排除 |
|---|---|
| `docs/dev-ops/interview-prep-resume-five-points.md` | 简历/面试话术、自评弱点、对外口径红线——个人求职材料 |
| `docs/dev-ops/M4-status-and-next-tasks.md` | 内部交接快照：分支状态、本机环境、并行会话/踩坑记录 |
| `docs/dev-ops/NEXT-SESSION-PROMPT.md` | 内部开工提示词：本机路径、代理端口、私人口令口径 |
| `docs/dev-ops/GAP-five-points-development-plan.md` | 内部开发计划：差距判定、待拍板决策、面试口径 |

> 这四份**仍留在私有仓库**继续作为交接/开发依据（GAP 文档仍是后续开发与测试的权威入口），只是不随公开仓库发布。
> 实施细节：脚本用 `git rm -f` 把它们从快照分支索引/工作树移除后再提交（私有仓库不受影响）。

### 3.3 开源前建议补齐（非阻塞）

- `README.md`（当前几乎为空）：项目定位、架构一图、本地启动、数据源接入说明；
- `LICENSE`（Apache-2.0，与上游一致）+ `NOTICE`（写明上游项目名与许可，不重复个人身份信息）；
- `SECURITY.md`（已随 PR `chore(privacy)` 提供）。

---

## 4. 终检脚本（可反复使用，也可当 pre-push 钩子）

```powershell
powershell -NoProfile -File docs/dev-ops/scripts/scan-secrets.ps1            # 工作树 + 全历史
powershell -NoProfile -File docs/dev-ops/scripts/scan-secrets.ps1 -TreeOnly  # 只查工作树（CI / 钩子用）
```

- **工作树命中 ⇒ 退出码 1**（必须修完再推）
- **仅历史命中 ⇒ 退出码 2**（提醒：该仓库不能直接转公开，须走 §3 快照）
- 已知安全占位符（`sk-your-openai-api-key`、`sk-REPLACE_WITH_YOUR_API_KEY`、`ALTAK-your-baidu-api-key`、`change-me-*`、`<你的…>`、`${VAR:}`、`your-api-key`、`none`）不会误报。
- 历史扫描用 `git log --pickaxe-regex -S<regex>`（**必须带 `--pickaxe-regex`**，否则 `-S` 按字面串匹配、永远不命中——这是本次踩到的坑）。

### 建议接成 pre-push 钩子

```sh
# .git/hooks/pre-push（本机，不入仓库）
#!/bin/sh
powershell -NoProfile -ExecutionPolicy Bypass -File docs/dev-ops/scripts/scan-secrets.ps1 -TreeOnly || exit 1
```

---

## 5. 防复发约定（写进团队规矩）

1. **任何口令/key 不进仓库**：一律 `${ENV:}` / `passwordEnv` 间接引用；仓库里只允许占位符（`change-me-*`、`<你的…>`、`sk-REPLACE_WITH_YOUR_API_KEY`）。
2. **不要把数据库整库 dump 提交**（`mysqldump` / Sequel Ace 导出物含 `ai_client_api.api_key`）；文档里只写"怎么导"，不写"导出的文件"。
3. **新增示例配置一律用 `.env.example`**，真实 `.env` 由 `.gitignore` 兜住（已配置）。
4. **提交前跑 `scan-secrets.ps1 -TreeOnly`**；CI 里也跑一遍（只查工作树，历史命中不阻断 CI）。
5. **开源仓库不带历史**；如需同步上游，用 `git remote add upstream` + `git fetch`，但**不要把上游历史合并进公开仓库**。
