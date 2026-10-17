# P1-2 交付：第三个数据源（PostgreSQL 支付流水）+ 通用字段过滤

> 本文档是 **P1-2 的开发与交接依据，自包含**。上游依据：
> `docs/dev-ops/P1-cross-source-correlation-design.md`（P1 跨源关联，已合入）、
> `docs/dev-ops/M4-datasource-health-design.md` §8 P1 ②。
>
> 状态：**已完成**（分支 `feat/p1-2-postgres-third-source`，从 V1 `cf0f000` 切出；PR 待开）。

---

## 1. 目标

M1 只验证过"接**第二个**源（MySQL）不改 Agent 侧代码"。本步通过接入**第三个数据源**（PostgreSQL，
新的 `ds_type` + 新的数据库引擎）把这条承诺钉死，并顺带把 M1 就定义好、却一直没接线的
**结构化过滤**（`EntityQuery.filters` / `FieldFilter`）真正用起来。

选"支付流水"而不是随便建张 demo 表，是因为它**对案情有真实价值**：

| 源 | 表 | 关键字段 | 与本案的关系 |
|---|---|---|---|
| 第二个源（已有） | `ai_order`（MySQL） | order_no / status / **fail_reason** | `NO20260327001` = FAILED / **PAY_TIMEOUT** |
| **第三个源（本步）** | `payment_transaction`（PostgreSQL） | order_no / pay_channel / status / **duration_ms** / **fail_code** | `NO20260327001` = WECHAT / TIMEOUT / **30012ms** / **GW_TIMEOUT** |

`order_no` 是**未脱敏**的跨源关联键（订单的 `user_id` 是脱敏的，没法跨源比对），
于是"下单失败"第一次有了闭环的第三方证据：**不是限流，是微信支付网关 30012ms 卡在 30s 阈值上超时**。

## 2. 验收与真实结果

| 验收点 | 结果 |
|---|---|
| 新 `ds_type` 被注册 | ✅ 启动日志 `数据源 Provider 注册成功 dsId=ds_pg_payment dsType=postgres（只读）`；注册器只加了 `case "postgres"` |
| 健康端点自动包含新源 | ✅ `summary: total=3 healthy=3`（`ds_pg_payment costMs=6`）；**未注入密码时**自动给出可操作 WARN + hint（见 §6.2） |
| Agent 侧零改动即产出新工具 | ✅ 执行阶段工具清单变成 `[query_logs, query_orders, query_payments, describe_entity, query_correlated_timeline]`——工具工厂/SSE/证据面板**一行都没改** |
| 通用过滤（M1 预留字段接线） | ✅ 模型用 `filters:[{"field":"status","value":"TIMEOUT","operator":"EQ"}]` 查询，后端渲染为 `AND status = ?` 参数绑定，只回收 1 条 |
| 跨源关联支持选择实体侧 | ✅ 模型用 `entityCode=payments` 把日志与**支付流水**对齐（此前只能"按 dsId 取第一个"） |
| 结论质量提升 | ✅ 从"支付侧原因未知"变为**闭环根因**：WECHAT 网关超时 30012ms（监督判定 PASS） |
| 不回归 | ✅ 单测 61 例全绿；前端 `npm run build` exit 0、`smoke.ts` ALL PASS |

## 3. 关键决策

### 决策 1：通用过滤用 `QueryResult`… 不，用 M1 就预留好的 `EntityQuery.filters`

`EntityQuery` 里一直有 `List<FieldFilter> filters`（含 `EQ/NE/GT/GTE/LT/LTE/LIKE/IN` 操作符白名单），
但从未接线。本步把它接上，**不新造一套过滤模型**：

- 模板里新增一个 **`${filters}` 占位符**（`EntityQuery.FILTERS_PLACEHOLDER`，定义在 domain）；
- **只有带该占位符的实体**才会在工具 schema 里暴露 `filters`——因此既有订单工具**行为完全不变**（零回归）；
- 字段白名单来自**字段字典**（`ai_data_source_field.is_filterable=1`），并用 `enum` 直接写进 schema，
  模型既不用猜字段名，也没机会拼出字典外的字段；
- 值一律参数绑定，并按字典里的 `field_type` 加显式类型转换（PG 里 `numeric = '199.00'` 会直接报错，
  必须 `?::numeric`；MySQL 用 `CAST(? AS DECIMAL(...))`）。

### 决策 2：模板渲染改为"按占位符出现顺序"扫描（`SqlTemplateRenderer`）

原来 `MysqlOrderProvider` 把占位符替换与参数顺序**写死**（`start, end, orderNo, orderNo, limit`），
一旦模板挪动 `${filters}` 的位置参数就会错位。新渲染器按模板出现顺序扫描、同步收集参数，
位置无关；对既有订单模板（无 `${filters}`）产出的 SQL 与参数**与改造前逐字节一致**（有单测钉住）。

### 决策 3：不改 Provider SPI，`ds_type` 分支是唯一扩展点

`PostgresEntityProvider` 与 `MysqlOrderProvider` 形状相同（只读、密码走 `passwordEnv`、SQL 只来自
`query_template`、`max_rows` 截断、敏感字段脱敏、行级 `_rawRef`），差别只在方言与过滤实现。
**刻意不抽基类**（降低改动风险），"抽关系型 Provider 基类"登记为后续项。

### 决策 4：新增类型时，M4 的健康提示规则要跟着放宽

M4 的 hint 规则原本写死 `"mysql".equals(dsType)`——新增 postgres 源时，同样的缺密码问题只会回 `null`，
白白丢掉 M4 最有价值的那条可操作提示。本步把规则 1/2 放宽为"任何走 JDBC + `passwordEnv` 的类型"
（`mysql` / `postgres` / `postgresql`），并补了 2 个单测。**这是"能力复用"必须付的税：新类型要让既有能力的判定条件也覆盖到它。**

## 4. 接口

**新工具**（模型可见）：`query_payments`（由 `query_ + entityCode` 自动生成）

```json
{
  "type": "object",
  "properties": {
    "startTime": {"type": "string"},
    "endTime":   {"type": "string"},
    "orderNo":   {"type": "string"},
    "filters": {
      "type": "array",
      "description": "可选的结构化过滤条件（多个条件之间是 AND）",
      "items": {
        "type": "object",
        "properties": {
          "field":    {"type": "string", "enum": ["order_no", "pay_channel", "status", "amount", "fail_code", "create_time"]},
          "operator": {"type": "string", "enum": ["EQ", "NE", "GT", "GTE", "LT", "LTE", "LIKE", "IN"]},
          "value":    {"type": "string"}
        },
        "required": ["field", "value"]
      }
    },
    "limit": {"type": "integer"}
  }
}
```

> `duration_ms` **不在** `enum` 里——字典里它的 `is_filterable=0`。schema 是字典生成的，
> 所以"哪些字段能过滤"改数据库即生效，不用改代码（这正是 M1 §7 想要的"字段字典驱动 schema"）。

**跨源对齐工具新增参数**：`query_correlated_timeline` 增加可选 `entityCode`
（不传时仍取第一个实体源；并会在结果告警里列出"其它可选实体源"）。

## 5. 接入点（改了什么）

| 文件 | 改动 |
|---|---|
| **新增** `infrastructure/.../PostgresEntityProvider.java` | PostgreSQL 只读实体 Provider（方言 POSTGRES，其余与 MySQL 源同构） |
| **新增** `infrastructure/.../SqlTemplateRenderer.java` | 模板扫描渲染 + `${filters}` 通用过滤（白名单/操作符/类型转换/参数绑定） |
| `MysqlOrderProvider` | 改用共享渲染器（行为与参数顺序不变），并支持 `filters` |
| `DataSourceProviderRegistrar` | 新增 `case "postgres"` |
| `EntityQuery` | 新增常量 `FILTERS_PLACEHOLDER`（放 domain，避免 domain 反向依赖 infrastructure） |
| `DataSourceHealthService.hintOf` | hint 规则 1/2 从"仅 mysql"放宽到"任何 JDBC + passwordEnv 类型" |
| `DataSourceToolCallbackFactory` | ① 枚举驱动：实体模板带 `${filters}` 才暴露 `filters`，`enum` 来自字段字典；② 解析 `filters` → `List<FieldFilter>`；③ 关联工具新增 `entityCode` |
| `CrossSourceCorrelationService` | 关联可选指定 `entityCode`（匹配不到时回退 + 告警），并在来源说明里列出其它可选实体源 |
| **新增** `docs/dev-ops/postgres/sql/upgrade-p1-2-payment-transaction.sql` | PG 侧建表 + 样例数据（幂等） |
| **新增** `docs/dev-ops/mysql/sql/upgrade-p1-2-postgres-payment-source.sql` | MySQL 侧登记数据源/实体/字段（幂等；**注意 `sensitive` 是 MySQL 8.0 保留字，必须加反引号**） |
| **新增** `test/dao/P1_2PaymentTransactionSeedTest.java` | 用 JDBC 执行 PG 建表脚本并自检（本机无 psql 客户端，这样可重跑） |
| **新增** `test/p1/SqlTemplateRendererTest.java`（12 例）、`M4HealthServiceTest` +2 例、`P1CrossSourceCorrelationTest` +3 例 | 单测 |
| 前端 `rawRef.ts` / `evidence.ts` / `smoke.ts` | `pg://` 引用解析；`filters` 渲染为可读条件（`status = TIMEOUT`、`pay_channel ∈ WECHAT,ALIPAY`）+5 断言 |
| `UPGRADE-hot-reload-deepseek.md` | §一 部署顺序新增两个 SQL 步骤 |

**不改**：Provider SPI、SSE 事件契约、`QueryResult` 结构、M3 证据派生逻辑、跨源关联的核心算法。

## 6. 验证证据

### 6.1 单测与前端

```
mvn -o -B test → Tests run: 61, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
（P1CrossSourceCorrelationTest 17、SqlTemplateRendererTest 12、M4HealthServiceTest 16、其余为既有用例）
npm run build → exit 0；node scripts/.smoke.mjs → ALL PASS
```
`SqlTemplateRendererTest` 重点守安全边界：**过滤值绝不出现在 SQL 文本里**（注入尝试 `TIMEOUT' OR '1'='1` 只进参数）、
字段不在字典/不可过滤/标识符非法 → 一律忽略并告警、数值字段带类型转换、`IN` 展开为多个占位符、
模板未启用 `${filters}` → 过滤被忽略**且告警**、未知占位符/空模板 → 抛错。

### 6.2 新源接入（M4 能力零适配）

故意**不注入** `PGVECTOR_PASSWORD` 启动：

```
INFO  数据源健康 dsId=ds_log_es dsType=elasticsearch costMs=144 entities=1
INFO  数据源健康 dsId=ds_order_mysql dsType=mysql costMs=37 entities=1
WARN  数据源不可用 dsId=ds_pg_payment dsType=postgres costMs=5024
      原因=健康检查失败 dsId=ds_pg_payment：Failed to obtain JDBC Connection
           （根因：The server requested SCRAM-based authentication, but the password is an empty string.）
      建议=未能取到连接密码：请注入环境变量 PGVECTOR_PASSWORD（来源 ai_data_source.conn_config.passwordEnv）
INFO  Application - Started Application in 13.665 seconds
```

注入后：`summary: total=3 healthy=3`，三个源全绿（`ds_pg_payment costMs=6`）。

### 6.3 真实端到端（真实 ES + MySQL + PostgreSQL + LLM）

提示词：排查 `NO20260327001` 支付超时根因（查订单 → **到支付流水筛 TIMEOUT** → 看渠道与网关耗时 → 可做时间对齐）。

模型实际调用（SSE 抓取）：

```json
{"toolName":"query_payments","dsId":"ds_pg_payment","entityCode":"payments",
 "arguments":"{\"orderNo\":\"NO20260327001\",\"startTime\":\"2026-03-27T00:00:00+08:00\",\"endTime\":\"2026-03-27T23:59:59+08:00\",
               \"filters\":[{\"field\":\"status\",\"value\":\"TIMEOUT\",\"operator\":\"EQ\"}]}",
 "result":"{\"columns\":[\"order_no\",\"pay_channel\",\"status\",\"amount\",\"duration_ms\",\"fail_code\",\"create_time\"],
   \"costMs\":29,\"dsId\":\"ds_pg_payment\",\"entityCode\":\"payments\",
   \"rawRef\":\"pg://ds_pg_payment/payments/payment_transaction@2026-03-26T16:00:00Z~2026-03-27T15:59:59Z\",
   \"rows\":[{\"order_no\":\"NO20260327001\",\"pay_channel\":\"WECHAT\",\"status\":\"TIMEOUT\",\"amount\":199.00,
              \"duration_ms\":30012,\"fail_code\":\"GW_TIMEOUT\",\"create_time\":1774578612000,
              \"_rawRef\":\"order_no=NO20260327001\"}],\"total\":1,\"truncated\":false,\"warnings\":[]}"}
```

同时模型用 `query_correlated_timeline(keyword=timeout, **entityCode=payments**, 5min 桶)` 做了跨源对齐
（日志侧 0 条、流水侧 4 条——并把"日志 0 条 ≠ 无关联"如实写进结论）。

最终结论（节选）：

> **直接根因：微信支付渠道（WECHAT）网关调用超时（GW_TIMEOUT），网关耗时 30012ms 恰好卡在 30 秒超时阈值上**，
> 触发系统超时熔断，订单被置为 FAILED / PAY_TIMEOUT。
> 时间线：`10:30:00 下单 → 10:30:12 发起 WECHAT 支付 → 30012ms 超时 → 订单 FAILED/PAY_TIMEOUT`

质量监督判定 **PASS**，并特别指出"证据链闭合：订单失败原因 ⇄ 流水状态 ⇄ 失败码 ⇄ 网关耗时四者相互印证"。
原始证据：`data/m4-evidence/p1-2-sse.txt`（`/data/` 已 gitignore）。

## 7. 边界与已知限制（**未做**，如实登记）

1. **`filters` 只对"模板带 `${filters}`"的实体开放**：订单实体的模板没带，因此 `query_orders` 的 schema
   与行为与之前完全一致（这是有意的零回归取舍）。要给订单也开过滤，只需在 `ai_data_source_entity` 的模板里
   加上 `${filters}`（改数据，不改代码），但需要一次真实的回归验证。
2. **`LIKE` 的值由调用方自带通配符**（后端原样绑定），没有做"自动加 `%`"；`IN` 用英文逗号分隔。
3. **过滤只支持 AND**，没有 OR / 嵌套条件（`FieldFilter` 也没有该表达）。
4. **没有跨源 JOIN**：`order_no` 已经是可用的关联键，但"按 key 关联两张表"还没做
   （当前关联仍是"时间桶对齐"）。
5. **多源联合对齐未做**：`entityCode` 只能选**一个**实体侧；日志侧仍只取第一个 `log_search` 源。
6. **关系型 Provider 未抽基类**：MySQL 与 PostgreSQL 两个 Provider 有约 60% 结构重复，后续值得抽 `AbstractJdbcEntityProvider`。
7. 新增类型时记得检查**已有能力的判定条件**是否覆盖到它（本步就补了 M4 的 hint 规则）——
   这条已作为"能力复用的税"写进决策 4。

## 8. 后续可做

| 候选 | 价值 |
|---|---|
| 按 `order_no` 做**跨源键关联**（订单 ⇄ 支付流水 ⇄ 日志） | 比时间桶更硬的证据；`order_no` 未脱敏，已具备条件 |
| 前端**双轴时间线视图**（含多源泳道） | P1 §7.6 的遗留：现在仍用表格呈现对齐结果 |
| 关系型 Provider 抽基类 | 降低后续接第四个 JDBC 源的成本 |
| 给订单实体也开 `${filters}` | 让"只看 FAILED 订单"由后端完成，减少模型侧筛选 |
| Loki / Prometheus 指标源 | 真正解锁前端 Metrics/Trace 查看器（需先解决本地实例/Docker） |
