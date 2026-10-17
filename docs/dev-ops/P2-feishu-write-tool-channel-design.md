# 设计：飞书写类 MCP 工具接入（与只读数据源 SPI 分离）

> 状态：**待评审**（未写代码）
> 关联：`M4-status-and-next-tasks.md` §10（ES MCP 核查）、`ES-MCP-exposure-verification.md`
> 本文的飞书包名/工具名/启动参数**均为实测取得**（`@larksuiteoapi/lark-mcp@0.5.1` 实装后从
> `dist/mcp-tool/constants.js`、`docs/tools-zh.md`、`README_ZH.md` 读出），非猜测。

---

## 0. 必须先解决：现有绊线会拦住本设计

`EsMcpToolExposureTest`（本次刚加、**尚未合入 V1**）含一条断言：

```java
clientNode_declaresNoMcpDependency()   // 断言 AiClientNode 不得声明任何 MCP 类型字段
```

**实测它有牙**：在 `AiClientNode` 加一个 `McpSyncClient` 类型字段 → 测试红，报
`AiClientNode 不应声明任何 MCP 类型依赖，实际=[mcpSyncClientProbe : ...McpSyncClient]`。

而"把飞书 MCP 工具给模型"改的**正是** `AiClientNode` 的工具注入路径。
→ **该断言必须先改造**，语义从「AiClientNode 不得碰任何 MCP」改为
「**AiClientNode 不得消费 ES MCP(5007)**」，并加「工具面精确集合 + 来源归属」断言。
否则实现飞书时它必红，最后会被人删掉——那才是真正的安全损失。

> 同一测试里还有 `mcpBeanNameList_hasNoConsumerInDeliveryPath()` 等断言，措辞都假定
> "模型可见工具 = 5 个只读语义工具"，同样需要随本设计调整。

---

## 1. 目标 / 非目标

### 目标
给模型一个**受控的外向写动作**：把本轮调查结论写入飞书文档。

### 非目标（明确不做）
- **不恢复**通用"MCP→模型"全量通道。那会让 ES MCP 的 11 个工具（含 `bulk`/`reindex`/
  `delete_index_template`）同时到达模型——已在 §10 核查过，当前它们是"未到达"状态。
- 飞书**不是数据源**：不实现 `IDataSourceProvider`，不进 `describe_entity` / 跨源对齐。
- **不放松**只读纪律：`DataSourceCapability` 不新增写能力、
  `DataSourceToolCallbackFactory` 仍只产只读语义工具、`EsQueryGuard` 不动。

---

## 2. 已查证的飞书 MCP（实测）

| 项 | 值 | 来源 |
|---|---|---|
| npm 包 | `@larksuiteoapi/lark-mcp`，`latest=0.5.1`（**飞书官方**，`larksuite` 组织） | `npm view` 实测 |
| 可执行 | `bin: {"lark-mcp": "dist/cli.js"}` | 同上 |
| 传输 | `--mode` 默认 `stdio` | `README_ZH.md` §参数表 |
| 启动 | `npx -y @larksuiteoapi/lark-mcp mcp -a <app_id> -s <app_secret>` | `README_ZH.md` |
| 工具选择 | `-t <逗号分隔的工具名或 preset>` | 同上 |
| 工具名格式 | `-c snake\|camel\|dot\|kebab`，**默认 snake** | 同上 |
| 语言 | `-l zh\|en`，默认 en | 同上 |
| 域 | `-d` 默认 `https://open.feishu.cn` | 同上 |

### `preset.doc.default` 实际包含 6 个工具（`dist/mcp-tool/constants.js` 实测）

```
docx.v1.document.rawContent         读：获取文档纯文本
docx.builtin.import                 写：导入文档（Markdown/HTML → 飞书文档）
docx.builtin.search                 读：搜索文档
drive.v1.permissionMember.create    ⚠️ 写：新增协作者（会改文档权限！）
wiki.v2.space.getNode               读：获取知识空间节点
wiki.v1.node.search                 读：搜索知识空间节点
```

### ⚠️ 因此**不能直接用 `-t preset.doc.default`**

`drive.v1.permissionMember.create` 会把文档**分享给其他成员**。对一个"排障助手"来说，
"改文档权限"是比"写文档"更高风险的动作，不该顺手给出。
→ 必须用 `-t` **显式逐个列出**允许的工具名，而不是用 preset。

### ✅ 上述已用 `tools/list` 运行时实测确认（非仅读常量表）

以占位凭据启动（`-a cli_probe_only -s probe_secret_only`，**只做 initialize + tools/list，不调用任何工具**）：

```
INITIALIZE_OK serverInfo={"id":"lark-mcp-server","name":"Feishu/Lark MCP Server","version":"0.5.1"}
TOOL_COUNT=3
TOOL: docx_v1_document_rawContent
TOOL: docx_builtin_search
TOOL: docx_builtin_import
```

三条结论（都是实测，不是推断）：

1. **工具名默认转成下划线 `snake`**：`docx.v1.document.rawContent` → `docx_v1_document_rawContent`。
   **白名单/回归网必须用这个注册名**，不能用点号原名。
2. **`-t` 显式列举确实排除了 `drive.v1.permissionMember.create`**：只有 3 个工具出现，
   权限工具不在其中 → §2 的排除策略有效。
3. 用占位凭据也能完成 `initialize` 与 `tools/list`（凭据只在真正调用 API 时才用）——
   所以"先列工具、后接凭据"这个实施顺序可行。


### 顺带查到的写类工具（`docs/tools-zh.md` 实测，**不在** preset 里）

```
docx.v1.document.create       创建文档（可选标题/文件夹）
docx.v1.document.convert      Markdown/HTML → 文档块
docx.v1.documentBlockChildren.create   创建块（往已有文档追加内容）
docx.v1.documentBlock.patch   更新块
docx.v1.documentBlockChildren.batchDelete  删除块
```

---

## 3. 架构：新增"动作类工具"通道

```
现状（只读）：
  ai_data_source_entity → IDataSourceProvider(SPI) → DataSourceToolCallbackFactory
                          └→ query_* / describe_entity / query_correlated_timeline
                               └→ AiClientNode.defaultToolCallbacks(...)

新增（写类，外向动作）：
  ai_client_tool_mcp → McpSyncClient（已存在）
                        └→ ActionToolCallbackFactory（新）：按 **显式允许清单** 过滤
                             └→ AiClientNode.defaultToolCallbacks(只读 + 动作，合并)
```

要点：
- `ActionToolCallbackFactory` 与 `DataSourceToolCallbackFactory` **并列**，不互相 import 业务语义。
- 唯一注入点仍是 `AiClientNode`。
- **白名单默认空**（fail-closed）：不显式配置就一个写工具都不出。忘记配置是安全的。

---

## 4. 允许清单放哪

已实测：`ai_client_config.ext_param`（varchar(1024)）**完全未被占用**
（39 行里 `ext_param` 非空计数 = 0）。`ai_client_tool_mcp` 与 `ai_client_config` 均无工具级白名单列。

| 方案 | 做法 | 评价 |
|---|---|---|
| **A（推荐）** | 用 `ai_client_config.ext_param` 存 `{"allowedTools":["docx.builtin.import", ...]}` | 无 DDL 变更；**按客户端绑定**（同一 MCP 可对响应端放开、对其他端不放），与现有 `ai_client_config` 授权模型一致 |
| B | 给 `ai_client_tool_mcp` 加列 `allowed_tools` | 是**全局**的，无法按阶段区分；且要动表结构 |
| C | 代码常量 | 不可运维，换工具要发版 |

**推荐 A。**

---

## 5. 谁可以调（阶段裁剪）

| client_type | 给飞书写工具？ | 理由 |
|---|---|---|
| `TASK_ANALYZER_CLIENT` | ❌ | 还没查就写 = 过早动作 |
| `PRECISION_EXECUTOR_CLIENT` | ❌（推荐） | 查询阶段不该产生副作用 |
| `QUALITY_SUPERVISOR_CLIENT` | ❌ | 监督无副作用 |
| `RESPONSE_ASSISTANT` | ✅ **唯一注入点** | 结论已成；且该阶段现有工具集为空（实测 `工具=[]`），加一个动作不干扰只读链路 |

**这一条比"MCP 层白名单"更强**：白名单只管工具名，阶段裁剪管"谁能调"。

---

## 6. 配置（实测 schema 对应）

`TransportConfigStdio.Stdio` = `{command, args[], env{}}`（`AiClientToolMcpVO.java` 实测），
`AiClientToolMcpNode` 把 `env` 直接传给 `ServerParameters.builder(...).env(...)`。

```json
{
  "lark-mcp": {
    "command": "D:\\node\\npx.cmd",
    "args": ["-y", "@larksuiteoapi/lark-mcp", "mcp",
             "-a", "cli_xxxx",
             "-t", "docx.builtin.import,docx.v1.document.rawContent,docx.v1.document.create",
             "-l", "zh", "-c", "snake"],
    "env": { "LARK_APP_SECRET": "见下方硬要求 2，不得明文入库" }
  }
}
```

> `-t` 里写的是**点号 API 原名**（CLI 侧标识）；注册给模型的**工具名**是下划线形式
> （`docx_builtin_import` 等，见 §2 实测）。**白名单与回归网用注册名。**

### ⚠️ 允许清单的一处修正（实测导致，**需你复核**）

你在 §8 同意的最小集是「`docx_builtin_import`（写）+ `docx_v1_document_rawContent`、
`docx_builtin_search`（读）」。实测后有两点必须改：

1. **`docx_builtin_search` 要去掉**：它的 schema/描述写明**只支持 `user_access_token`**，
   应用身份下调用必然失败。若保留用户 OAuth 方案（§8.2 的 A3）才可加回。
2. **要加 `docx_v1_document_create`**：因为写工具 `docx_builtin_import` **没有位置参数**
   （实测），"用户自定义导入位置"只能靠 `docx.v1.document.create` 的 `folder_token` 实现（§8.2）。

→ 修正后的最小集 = **`docx_builtin_import`（写正文）+ `docx_v1_document_rawContent`（回读校验）**，
  是否再加 `docx_v1_document_create` / `drive_v1_file_move` **取决于 §8.2 的流甲/乙哪个可行**
  （未验证项 4）。**仍然排除** `drive.v1.permissionMember.create`。

### ⚠️ 凭据处理的硬要求

1. **`app_secret` 绝不能进命令行参数** —— 当前实现会把 `ServerParameters` 打日志/异常里，
   命令行参数会出现在进程列表与诊断信息中。**必须走 `env`**。
2. 但**当前 `transport_config` 里的 `env` 是明文存库**的。所以更进一步：
   与订单源同款做法，**`app_secret` 走进程环境变量**
   （如 `DS_FEISHU_APP_SECRET`），由 `AiClientToolMcpNode` 在构造 stdio 时从环境读取，
   或对 `transport_config` 里的 `${ENV_NAME}` 做占位符替换。
   **本次需新增这段能力**（现有实现不做任何替换——已实测确认）。
3. `app_id` 不是密钥，可明文入库。
4. ES MCP 的 `ES_API_KEY` 明文入库问题已有先例（`enable-es-mcp-local.sql` §5 建议移除），
   不要重复这个坑。

### 幂等（必须显式设计）

模型可能在一轮里多次调用导入 → 会生成多份重复文档。需要幂等键，
建议 `sessionId + turn`（与前端 `Session.turns` 分层取值语义一致）：
同一轮重复调用直接返回首次结果，不重复创建。

---

## 7. 写什么内容（纪律）

- 写**当前轮**的结论/诊断/命中证据摘要（沿用现有分层取值：证据看全会话、结论只看当前轮）。
- **禁止**写入 `rawRef` / `_rawRef`（项目铁律：它们只给证据面板，不进模型提示词，
  同样不该被模型转写进外部文档）。
- **禁止**写入 `conn_config`、任何密码、`auth` 头。
- 脱敏字段（如 `user_id` 已是 `****`）按现状写入，**不还原**。
- 建议正文用 Markdown，因为 `docx.builtin.import` 原生接受 Markdown/HTML。

---

## 8. 已拍板的决策（2026-09-26）

| # | 决策 | 影响 |
|---|---|---|
| 1 | **写入时机由用户控制**；每次**新建文档**写入 | 不能"自动每轮写"；需要一个用户主动触发点 → §8.1 的审批流 |
| 2 | 允许清单最小集（`docx_builtin_import` + 两个只读），**明确排除** `drive.v1.permissionMember.create` | 白名单初值已定 |
| 3 | **要人在回路**（写前确认） | 后端当前**只有单向 SSE**，需新增审批机制 → §8.1 |
| 4 | 凭据："不懂" | 需给出获取步骤 → §8.2 |
| 5 | **用户自定义导入位置 → 定为 A1**：运维配置一个**应用文件夹的 `folder_token`**，界面上不选位置 | 写工具 `docx_builtin_import` 实测**无位置参数**，故位置只能靠"落到应用文件夹"实现；A1 = 零 OAuth。**代价：位置不是终端用户可选**（详见 §8.2） |

### 8.1 人在回路 + 用户控制时机：需要新机制（**本设计最大的工程量**）

**已实测的现状**：`AutoAgentExecuteStrategy` 只持有 `ResponseBodyEmitter`，
全程只有 `emitter.send(...)` 单向下发；确认环节需要**客户端 → 服务端**的反向通路，
当前**没有**。所以这一步不是"加个按钮"，要新增机制。

**推荐方案：待确认动作 + 审批端点（服务端暂停）**

```
1) 模型在 RESPONSE_ASSISTANT 阶段"调用"写工具 → 不真正写，而是登记一个待确认动作：
     {actionId, sessionId, turn, targetTool, 拟写入正文(服务端生成、不可由客户端改),
      status=PENDING, 过期时间}
   并发 SSE 事件 `action_proposal`（含 actionId、正文预览）
2) 前端弹确认框：显示完整正文预览；用户「确认 / 取消」
3) 用户确认 → POST /api/v1/agent/action/{actionId}/confirm
4) 服务端执行真实 MCP 调用（docx_builtin_import）→ 回填结果 + SSE `action_result`
```

必须显式设计的点：

| 点 | 要求 |
|---|---|
| **超时** | 待确认动作必须有 TTL，过期作废并下发 `action_expired`；不能无限挂起 |
| **线程模型** | **不要**阻塞 `threadPoolExecutor` 线程等用户。模型那一步返回"已登记待确认"即可结束本轮，真实写入在 confirm 端点做 |
| **一动作一次** | `actionId` 一次性消费；重复 confirm 幂等返回首次结果 |
| **越权** | confirm 必须校验 actionId 存在且 `status=PENDING`，不能拿任意参数直接触发写 |
| **正文可信** | 正文在**登记时**由服务端固定；confirm **只能确认或取消，不能改正文** —— 否则等于开了任意写接口 |
| **前端** | 新增确认 UI + 正文预览（复用现有 Markdown 渲染）；按轮归属（`Session.turns` 体系） |

> 备选（更简单但不推荐）：不做模型工具调用，改为前端"导出到飞书"按钮，后端直接写入。
> 绕开审批机制与 MCP 通道，但模型无法参与决定写什么，飞书 MCP 通道也就失去意义。

### 8.2 ⚠️ 「用户自定义导入位置」比预想复杂（实测发现）

**关键实测结果**：写工具 `docx_builtin_import` 的入参 schema **只有两个字段**：

```json
{ "data": { "markdown": "string（必填）", "file_name": "string（maxLength=27）" } }
```

**它没有任何位置/文件夹参数** —— 即"导入"本身只能落在默认位置（应用根目录）。
所以"用户自定义导入位置"需要额外机制。查到的可选路径（均已实测 schema）：

| 路径 | 工具 | 位置参数 | 身份要求 |
|---|---|---|---|
| **A（推荐）** | `docx.v1.document.create` | `data.folder_token`（不传=根目录）+ `data.title` | `tenant_access_token` 时**只能指定"应用自己创建的文件夹"** |
| B | 先 `docx_builtin_import` 再 `drive.v1.file.move` | `drive.v1.file.move` 需 `path.file_token` + `data.folder_token` + `data.type` | 移动的是"用户云空间"位置，**很可能需要 user 身份**（未实测） |
| C | 不用导入，用 `docx.v1.document.create` + `docx.v1.documentBlockChildren.create` 逐块写 | 同样有 `folder_token` | 同 A，但正文要自己拼块结构，**比 Markdown 麻烦得多** |

**推荐 A**：`docx.v1.document.create(folder_token, title)` 创建到指定文件夹，
再用块接口或 convert 写入内容。但注意 A 在**应用身份**下的硬限制：
**只能指定"应用自己创建的文件夹"**。

→ 因此"用户自定义导入位置"有三种现实语义。**已定案：A1。**

- ✅ **A1（已选定案）**：后端使用一个**预先创建好的应用文件夹**，其 `folder_token`
  作为**配置项**（不是界面上让终端用户选）。零 OAuth 成本。
- ❌ A2（未选）：终端用户在确认框里粘贴 `folder_token`。
- ❌ A3（未选，独立大课题）：写进"用户自己的云空间任意位置" → 必须引入用户 OAuth
  （`lark-mcp login` / `--oauth`），连带 token 存储/刷新/多用户隔离。

#### A1 定案后的一个待定细节（**更正我先前写窄的说法**）

A1 下"把新建文档放进应用文件夹"有三种流，**都还没实测**（缺凭据）：

| 流 | 步骤 | 依赖工具 | 备注 |
|---|---|---|---|
| 甲 | `docx_builtin_import`（产 Markdown 正文）+ `drive_v1_file_move` 移入文件夹 | import + move | **最省事**；但"应用身份能否移入应用自己创建的文件夹"**未实测**（§10 未验证项 4） |
| 乙 | `docx_v1_document_create(folder_token)` + 块写入正文 | create + block 创建/convert | 位置确定，正文要自己拼块结构或先 convert |
| 丙 | 其他组合 | create + … | 同乙 |

> **更正**：我先前说"A1 下 `docx_v1_document_create` 可以不要"——**这话不准确**。
> A1 仍需要"把文档落到应用文件夹"的某一步：走流乙/丙必须保留 `docx_v1_document_create`；
> 走流甲则可不保留 create，但要加 `drive_v1_file_move`。
> **哪个流可行取决于未验证项 4**，故白名单最终形态要拿到凭据实测后再冻结（§9 第 3 步）。

> 另外实测：`docx_builtin_search` 描述里写明**只支持 `user_access_token`**。
> 应用身份下调用必然失败 → 已从最小集移除。若将来启用 A3（用户 OAuth）才可加回。
> `docx.v1.document.rawContent` 的 schema 无此限制（回读校验可用应用身份）。

> **`file_name` maxLength = 27**：文档标题被飞书限制在 27 字符。生成标题必须截断，
> 否则调用失败。必须写成显式规则并单测。

### 8.3 关于凭据（回答"不懂"）

在**飞书开放平台**（`open.feishu.cn`）拿，步骤：

1. 「开发者后台」→ 创建**企业自建应用**。
2. 「凭证与基础信息」页可看到 **App ID**（形如 `cli_xxxxxx`）与 **App Secret**。
   - App ID 不是密钥，可明文配置；**App Secret 是密钥**，按 §6 走环境变量，不进库、不进命令行。
3. 「权限管理」开通文档权限，至少 `docx:document`；若要"应用自己创建文件夹"，
   还需云空间建文件夹相关权限（**具体权限点未实测**，见 §10）。
4. 「版本管理与发布」**发布应用**（未发布时权限不生效）。

拿到后先做**最小验证**（不改代码）：

```powershell
$env:LARK_APP_SECRET='<你的 secret>'
npx -y @larksuiteoapi/lark-mcp mcp -a <你的 app_id> -s $env:LARK_APP_SECRET `
  -t docx.builtin.import,docx.v1.document.rawContent -l zh
# 能 initialize 且不报鉴权错 → 凭据与权限基本可用
```

→ **在做到这一步之前我不需要你的任何密钥。** 你可以先按 1~4 在飞书后台点；
卡住就把界面情况告诉我。

---

## 9. 实施顺序（评审通过后）

1. **先改造 §0 的绊线**（否则后续必假红）：
   - `clientNode_declaresNoMcpDependency` → 改为"不得消费 ES MCP 5007"；
   - 工具面断言改为**精确集合 + 来源归属**。
2. `AiClientToolMcpNode` 支持 `transport_config` 里的 `${ENV_NAME}` 占位符替换（§6.2），
   并补单测：env 有值 → 替换；env 缺失 → **明确报错**（不要静默传字面量 `${...}`）。
3. 实现 `ActionToolCallbackFactory`：
   - 白名单来自绑定的 `ext_param`，**默认空**；
   - 只对 `RESPONSE_ASSISTANT` 生效。
4. `AiClientNode` 合并只读 + 动作工具。
5. 落库：新增 `ai_client_tool_mcp` 记录（建议 `mcp_id=5008`）+ 只绑 `client_id=4104` 的
   `ai_client_config` 记录（`source_type='client'`，`target_type='tool_mcp'`，`ext_param` 放白名单）。
6. 单测：
   - 白名单为空 → 动作工具 0 个；
   - 白名单命中 → 恰好命中的那些；
   - 非 `RESPONSE_ASSISTANT` → 0 个；
   - **ES MCP 工具任何情况都不得出现**（回归网）；
   - `${ENV_NAME}` 替换成功/缺失两条路径。
7. **审批机制**（§8.1，工程量最大，可与 1~6 并行开发）：
   - 待确认动作登记（内存实现即可起步，参考 `BeanRegistry` 的锁纪律）；
   - SSE 新事件 `action_proposal` / `action_result` / `action_expired`；
   - 端点 `POST /api/v1/agent/action/{actionId}/confirm`、`.../cancel`；
   - TTL 清理；`actionId` 一次性消费；confirm **不接受客户端改正文**；
   - 单测：过期动作不可确认；重复确认幂等；正文不可被客户端篡改；非 PENDING 不可确认。
8. **写入实现**（依赖 §8.2 的位置方案 A1/A2/A3 定案）：
   - `file_name` 超 27 字符必须截断（显式规则 + 单测）；
   - 若用 `docx.v1.document.create`：`folder_token` 格式校验（不可信输入，不得直拼）；
   - `folder_token` 来源为配置（A1）还是用户输入（A2）由此步确定。
9. 前端确认 UI：正文预览（复用现有 Markdown 渲染）+ 确认/取消。
10. 端到端：真实调查 → 模型提议 → 界面确认 → 真实写飞书新文档 → 人工核对正文与脱敏；
    再验「取消不产生文档」「重复点击只产生一份」「标题超 27 字符被正确截断」。
11. 文档：把"三个数据源走 SPI / MCP 仅用于外向动作"写进 M1/M4 文档，消除歧义。

---

## 10. 已知风险

| 风险 | 说明 |
|---|---|
| `npx` 首次启动慢 | ES MCP 已踩过（首次启动约 9 秒），会影响启动期装配；可改绝对路径缓解 |
| 网络依赖 | 飞书是**外部 SaaS**，与 ES/MySQL/PG 的本地依赖不同；失败会让 `RESPONSE_ASSISTANT` 阶段多一个不可控因素 |
| `-t` 与工具名大小写 | **已实测解决**：默认 `snake`，注册名为 `docx_builtin_import` / `docx_v1_document_rawContent` / `docx_builtin_search`。白名单必须用注册名 |
| **审批机制改变执行链语义** | 现在"一轮 = 跑完 4 步 + complete"是同步的；引入待确认动作后，一轮可能停在"等待人确认"。前端 `Session.turns` 与 `investigation.ts` 的分轮逻辑需要同步适配（否则会出现"半完成轮"的展示问题） |
| **未验证项（1）** | 飞书 MCP 的 **`initialize`/`tools/list` 已实测通过**（占位凭据）；但**真正调用** `docx_builtin_import`（即真实写文档）**未验证** —— 缺可用 `app_id`/`app_secret` 与 `docx:document` 权限 |
| **未验证项（2）** | **已解决**：`docx_builtin_import` 入参实测为 `{markdown*, file_name(≤27)}`，**无位置参数**。位置方案与身份限制见 §8.2（`docx.v1.document.create` 有 `folder_token`，但应用身份下只能指定"应用自己创建的文件夹"） |
| **未验证项（3）** | 导入/建文件夹所需的**具体飞书权限点**未实测（§8.3 步骤 3） |
| **未验证项（4）** | `drive.v1.file.move` 在应用身份下能否把文档移入"应用自己创建的文件夹"之外的**用户云空间**位置 —— **未实测**，这决定 §8.2 路径 B 是否可行 |
| **未验证项（5）** | `docx.v1.document.create` + 块写入（§8.2 路径 A/C）的**端到端真实写文档**未跑通 —— 缺有效凭据 |

