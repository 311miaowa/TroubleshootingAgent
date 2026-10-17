# Troubleshooting-Agent

> 面向**线上故障根因定位**的 Agent 应用：统一接入多源数据 → 把数据变成模型可用的语义工具 → 四步执行链编排
> （分析 / 执行 / 质检 / 回流）→ 输出**可回溯到原文**的排障结论（SSE 流式 + 证据面板）。

---

## 能力概览

| 能力 | 一句话 | 代码入口 |
|---|---|---|
| **统一数据源接入** | 抽象 `IDataSourceProvider` SPI（`log_search` / `entity_query` / `entity_meta` 能力枚举）+ 内存注册表 + 统一查询门面；接新数据源只加一个 Provider 类，Agent 侧零改动。已接入 Elasticsearch / MySQL / PostgreSQL | `domain/.../adapter/port/datasource/**`、`infrastructure/.../gateway/datasource/**` |
| **语义工具层 + 阶段权限** | 数据不再直连暴露：按 `<数据源 + 实体>` 自动生成只读语义工具；`ai_stage_tool_policy` 按阶段授权（**默认拒绝**），分析/质检阶段只给 `entity_meta`，只有执行阶段能查数据 | `DataSourceToolCallbackFactory`、`ai_stage_tool_policy` |
| **四步执行链** | 分析 → 执行 → 质检 → 回流/总结；质检三态 `PASS/FAIL/OPTIMIZE`，不通过则改写任务描述回流；`maxStep` 硬熔断 | `domain/.../service/execute/auto/step/**` |
| **证据可追溯** | 结果级 `rawRef` + 行级 `_rawRef` 两级回指（前端可跳 Kibana 核对原文）；`rawRef` **永不进提示词**（事件面/模型面双通道） | `QueryResult#toModelJson`、`web/src/utils/rawRef.ts` |
| **查询安全** | SQL 只从 `query_template` 渲染 + 值全量参数绑定 + 字段字典白名单；ES 侧硬编码 `POST /{index}/_search` + 索引名正则 + DSL 键白名单 | `SqlTemplateRenderer`、`EsQueryGuard` |
| **数据源健康** | `GET /api/v1/datasource/health` 返回 `summary.total/healthy/unhealthy` + 每源原因与可操作 hint（如"注入哪个环境变量"） | `DataSourceHealthController`、`DataSourceHealthService` |

## 模块结构

```
ai-agent-station-study-api            对外 DTO / 服务接口
ai-agent-station-study-app            启动模块（配置、装配入口、MyBatis mapper）
ai-agent-station-study-domain         领域层：SPI 端口、四步链、语义工具工厂、证据值对象
ai-agent-station-study-infrastructure 基础设施：DAO/仓储、ES/MySQL/PG Provider、SQL 渲染、ES 守卫
ai-agent-station-study-trigger        HTTP 入口（AutoAgent SSE、数据源健康、压测端点）
ai-agent-station-study-types          通用类型与枚举
web                                   前端（React + Vite + antd）：事件流时间线 + 证据面板
docs/dev-ops                          设计文档、部署脚本、SQL 升级脚本
```

一次排障请求的链路：
`POST /api/v1/agent/auto_agent` → 四步链（分析→执行→质检→回流）→ 执行阶段经语义工具调 `DataSourceQueryService`
→ Provider（ES/MySQL/PG）→ `QueryResult`（裁剪/脱敏/回指）→ **模型版**进提示词、**事件版**走 SSE 给证据面板。

## 快速开始

### 1. 依赖的中间件

| 中间件 | 端口 | 说明 |
|---|---|---|
| MySQL | 3306 | 配置库（`ai_agent` / `ai_client_*` / `ai_data_source*` / `ai_stage_tool_policy`） |
| PostgreSQL | 15432 | 支付流水数据源（`ai-rag-knowledge` 库）；可选 RAG 向量库 |
| Elasticsearch | 9200 | 日志数据源（本地默认无认证） |

### 2. 环境变量（**仓库里不放任何口令**）

```bash
MYSQL_PASSWORD=...              # 配置库账号口令
DS_ORDER_MYSQL_PASSWORD=...     # 订单只读账号（ai_ro）口令，对应 ai_data_source.conn_config.passwordEnv
PGVECTOR_PASSWORD=...           # PostgreSQL 口令（支付源 / 可选 RAG）
# 对话模型 key 不在这里：它在数据库 ai_client_api.api_key（见第 4 步）
```

### 3. 建库

按顺序执行（幂等脚本，`docs/dev-ops/mysql/sql/`）：

```
ai-agent-station-study.sql                     # 建库基线（11 张配置表 + 必需种子行）
upgrade-m0-1-flow-config-update-time.sql       # 热更新需要 ai_agent_flow_config.update_time
upgrade-m1-0-datasource-tables.sql             # 数据源 4 张表 + 阶段-工具策略表 + 种子
upgrade-m1-1-align-es-log-entity.sql           # ES 实体对齐（physical_name = group-buy-market-log-*）
upgrade-m1-3-order-table.sql                   # 订单只读表 + ai_ro 只读账号（口令请自行设置）
upgrade-m3-0-es-direct-conn.sql                # ES 直连 _search 的 conn_config
upgrade-m3-2-order-filter.sql                  # 订单过滤
upgrade-p1-1-cross-source-correlation.sql      # 跨源关联能力授权
upgrade-p1-2-postgres-payment-source.sql       # 第三个数据源（PostgreSQL）
switch-to-deepseek.sql                         # 切换对话模型到 DeepSeek（把占位 key 换成你自己的）
```

PostgreSQL 侧另需执行 `docs/dev-ops/postgres/sql/upgrade-p1-2-payment-transaction.sql`。
完整部署顺序（含热更新、可选脚本与回滚）见 [UPGRADE-hot-reload-deepseek.md](docs/dev-ops/UPGRADE-hot-reload-deepseek.md)。

### 4. 配置对话模型

`ai_client_api` 表里 `api_id=1001` 存 `base_url` / `api_key`（默认已指向 `https://api.deepseek.com`，key 为占位）。
把占位换成你自己的 key（**只改库，不要提交进仓库**）：

```sql
UPDATE ai_client_api SET api_key = 'sk-你的key' WHERE api_id = '1001';
```

### 5. 起后端 / 前端

```bash
mvn -o -B -DskipTests package
java -Djava.io.tmpdir=<repo>/data/tmp -jar ai-agent-station-study-app/target/ai-agent-station-study-app.jar
# 后端 8099；健康检查：
curl http://127.0.0.1:8099/api/v1/datasource/health

cd web && npm install && npm run dev        # 前端 http://localhost:5173
```

### 6. 跑测试

```bash
mvn -o -B test        # 80 个纯单测（不依赖任何外部服务；集成测试默认排除，可用 -Dtest=Xxx 单跑）
```

## 怎么接入一个新数据源

1. 实现 `IDataSourceProvider`（按能力再叠加 `ILogSearchProvider` / `IEntityQueryProvider`）；
2. 在 `DataSourceProviderRegistrar` 的 `ds_type` 分派里加一个 `case`；
3. 往 `ai_data_source` / `ai_data_source_entity` / `ai_data_source_field` 写配置（实体、查询模板、字段字典）；
4. Agent 侧（四步链、提示词、工具生成）**不需要改动**——语义工具由工厂按 `<数据源+实体>` 自动生成。

## 文档索引（本快照包含的部分）

| 文档 | 内容 |
|---|---|
| [M1-unified-datasource-design.md](docs/dev-ops/M1-unified-datasource-design.md) | 统一数据源接入层 + 工具层治理的设计依据 |
| [M3-evidence-panel-design.md](docs/dev-ops/M3-evidence-panel-design.md) | 证据面板、`rawRef` 双通道、事件契约 |
| [M4-datasource-health-design.md](docs/dev-ops/M4-datasource-health-design.md) | 数据源健康端点、启动期 WARN、hint 规则、热更新（T2） |
| [P1-cross-source-correlation-design.md](docs/dev-ops/P1-cross-source-correlation-design.md) | 跨源时间桶对齐（只做机械对齐，不做因果推断） |
| [P1-2-postgres-third-source-design.md](docs/dev-ops/P1-2-postgres-third-source-design.md) | 第三个数据源（PostgreSQL）与字段字典驱动过滤 |
| [P1-3-timeline-view-design.md](docs/dev-ops/P1-3-timeline-view-design.md) | 前端双轴时间线视图 |
| [P2-feishu-write-tool-channel-design.md](docs/dev-ops/P2-feishu-write-tool-channel-design.md) | 写类工具通道设计（与只读 SPI 严格分离，待实现） |
| [UPGRADE-hot-reload-deepseek.md](docs/dev-ops/UPGRADE-hot-reload-deepseek.md) | 热更新与部署顺序、回滚 |
| [ES-MCP-exposure-verification.md](docs/dev-ops/ES-MCP-exposure-verification.md) | ES MCP 暴露面核查（为什么模型看不到写工具） |
| [OPENSOURCE-CHECKLIST.md](docs/dev-ops/OPENSOURCE-CHECKLIST.md) | 开源前的凭据/隐私处置清单与快照流程 |
| [SECURITY.md](SECURITY.md) | 安全策略、边界与已知待办 |

> 说明：本仓库以**无历史快照**形式发布，且**不包含**内部交接/计划类文档（交接记录、开发计划、面试准备等）。

## 许可与安全

- 许可：Apache License 2.0（见 [LICENSE](LICENSE)）
- 安全策略与漏洞报告：见 [SECURITY.md](SECURITY.md)
- 归属与署名：见 [NOTICE](NOTICE)
