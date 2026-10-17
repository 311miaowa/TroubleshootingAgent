# 安全策略（Security Policy）

## 报告安全问题

- **漏洞 / 凭据泄露 / 越权读取**：请走 GitHub 的
  [Private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/about-coordinated-disclosure-of-security-vulnerabilities)
  （仓库页 `Security` → `Report a vulnerability`），或开一个**不含敏感细节**的 issue 约私下沟通。
- 请在报告里写清：**影响面**（哪个模块/端点）、**复现步骤**、**是否需要凭据**；不要粘贴真实凭据。
- 我们会尽快确认；确认前请不要公开披露。

## 本项目的安全边界（先看这个再说"漏洞"）

| 机制 | 现状 |
|---|---|
| 模型工具面 | 只读语义工具（`log_search` / `entity_query` / `entity_meta`），**写类能力在类型层面不存在**（`DataSourceCapability` 刻意只有 3 个只读能力） |
| SQL 侧 | 只从 `query_template` 渲染 + 值全量参数绑定 + 字段字典白名单（`SqlTemplateRenderer`） |
| ES 侧 | 请求方法/路径硬编码 `POST /{index}/_search` + 索引名正则 + DSL 键白名单（`EsQueryGuard`） |
| 证据回指 | `rawRef` / `_rawRef` **永不进模型提示词**（`QueryResult.toModelJson()` 剥离），只走 SSE 事件给前端 |
| 数据源账号 | 密码一律走环境变量（`conn_config.passwordEnv`），**仓库里不放任何口令**；JDBC 连接只读（`setReadOnly(true)`） |
| 已知未做 | **ES 服务端安全特性未启用**（仅应用层守卫）；多租户未实现（`tenantId` 固定 `default`）。这两条是已登记待办，不是未知漏洞 |

## 凭据纪律（贡献者必读）

1. **不要把任何 key / 口令提交进仓库**：示例配置用占位符（`change-me-*`、`<你的…>`、`sk-REPLACE_WITH_YOUR_API_KEY`）或 `${ENV_VAR}` 引用。
2. **不要提交数据库整库导出**（`mysqldump` / Sequel Ace dump 会把 `ai_client_api.api_key` 一起带出来）。
3. 提交前请跑一次凭据扫描：
   ```powershell
   powershell -NoProfile -File docs/dev-ops/scripts/scan-secrets.ps1 -TreeOnly
   ```
   工作树命中会以退出码 1 失败；建议接成 `.git/hooks/pre-push`（脚本为纯 ASCII，兼容 Windows PowerShell 5.1 与 PowerShell 7）。

## 关于本仓库的 git 历史（开源说明）

- 本仓库（私有）的历史中包含**上游课程作者演示环境用过的真实密钥**（详见
  `docs/dev-ops/OPENSOURCE-CHECKLIST.md` §1.1）。因此**公开版本采用"快照式开源"**：
  只发布一条**无父提交**的快照，不带任何历史。
- 若你在公开仓库里看到历史提交 —— 那不是本项目的发布形态，请提醒维护者。
- 这些密钥属于上游作者，本项目**无法代为轮换**，而"通报/工单"对本项目也没有实际作用。
  我们这边的处置只有两条（见上述清单 §1.3/§1.4）：**当前树直接移除**（已做）+ **不发布这段历史**（快照式开源）。
