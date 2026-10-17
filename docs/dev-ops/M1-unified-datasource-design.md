# M1 详细设计：统一数据源接入层 + 工具层治理

> 本文档是 M1 的开发依据。新会话请先读本文档，再读「0. 交接信息」。

---

## 0. 交接信息（现状与起点）

### 仓库与分支

- 项目：`ai-agent-station-study` 二次开发版（DDD 六模块，Spring Boot 3.4.3 + Spring AI 1.0.0 + MyBatis + MySQL + pgvector）
- 远端：私有仓库（地址不在公开文档中重复给出）
- **集成分支**：`V1-for-secondary-development`（只通过 PR 合入，**不要直接在上面提交**）
- **当前开发分支**：`feat/m1-unified-datasource`，基点 = `0b45d11`
- 已知的独立历史分支（**不要**作为开发基点）：`V1-secondary-dev-old-history`（2d6d153）、`main`（d5a1653）

### 已完成并合并的工作

| PR | 内容 |
|---|---|
| **#1**（`be4fe5d`） | 配置热更新修复（全表版本号检测 + Bean 替换空窗期消除）、`RagAnswerAdvisor` 两处缺陷、MCP stdio 配置查找、对话/向量化解耦（支持 DeepSeek）、两个单测、三个 SQL 脚本 |
| **#2**（`0b45d11`） | 升级部署说明 `docs/dev-ops/UPGRADE-hot-reload-deepseek.md` |

### 关键产物位置

| 文件 | 作用 |
|---|---|
| `docs/dev-ops/UPGRADE-hot-reload-deepseek.md` | **部署顺序**、环境前提、已知问题 |
| `docs/dev-ops/mysql/sql/upgrade-m0-1-flow-config-update-time.sql` | 给 `ai_agent_flow_config` 补 `update_time` |
| `docs/dev-ops/mysql/sql/switch-to-deepseek.sql` | 对话模型切 DeepSeek |
| `docs/dev-ops/mysql/sql/enable-es-mcp-local.sql` | 启用本地 ES MCP（含回滚） |
| `domain/.../armory/registry/BeanRegistry.java` | 动态 Bean 注册（全局锁） |
| `app/src/test/.../armory/BeanRegistryConcurrencyTest.java` | 并发替换回归测试 |
| `app/src/test/.../armory/RagAnswerAdvisorTest.java` | Advisor 行为单测 |

### 上一阶段回归暴露的两个问题（**M1 必须解决**）

1. **工具未按阶段裁剪 → 四步执行链被短路**：`AiClientNode`（客户端级）与 `AiClientModelNode`（模型级）都注入 MCP 工具，而一个 model 被 4 个 client 共享 → **四个客户端全部拿到 ES 工具**，包括"分析"和"质量监督"客户端。分析阶段自己完成了检索并输出 `任务状态: COMPLETED`，导致阶段 2/3 与回流被整段跳过 —— 即「质量监督判断证据不足自动回流」实际失效。
   **实测证据**：日志只出现 `阶段1 → 阶段4`，缺 `阶段2`、`阶段3`。
2. **ES MCP 暴露 6 个写/删工具**（`create_index` / `create_mapping` / `bulk` / `reindex` / `create_index_template` / `delete_index_template`），而默认 ES **未启用安全特性**（无认证），没有第二道防线。

### 环境事实（会导致命令失败，务必先看）

1. **编译必须用 JDK 17 / 21**：本机默认 JDK 25 下 Lombok 1.18.36 不生效，`ResponseCode` 编译失败。
   已可用的 JDK：`D:\develop\IntelliJ IDEA 2025.3.2\jbr`（21.0.9）
   ```powershell
   $env:JAVA_HOME='D:\develop\IntelliJ IDEA 2025.3.2\jbr'
   ```
2. **Windows 上 MCP 启动命令不能是裸 `npx`**：Java `ProcessBuilder` 抛 `CreateProcess error=2`，必须 `npx.cmd` 或绝对路径。
3. **`mvn test` 不会跑测试**：`ai-agent-station-study-app/pom.xml` 的 surefire 配置里**硬编码**了 `<skipTests>true</skipTests>`（`-DskipTests=false` 覆盖不了）。要跑测试请从 IDE 运行，或用 `JUnitCore` 直接执行。
4. **Docker 编排要用 aliyun 版**：`docker-compose-environment-aliyun.yml` 里 pgvector 映射的是 `15432:5432`（与应用配置一致），普通版是 `5432` 对不上；ELK 同样用 `-aliyun` 版。MySQL 是**本机原生服务**（3306），不要再起 compose 里的 mysql（aliyun 版映射 13306，不冲突）。
5. **应用可不依赖任何 LLM api-key 启动**（已实测）。对话用的 key 存在数据库 `ai_client_api` 表。
6. 离线构建：`mvn -o -B -DskipTests test-compile`

### 工具链坑（本会话踩过的）

- `... | Select-Object -First N` 会**提前终止上游管道**，把 `mvn` 一起杀掉 → 用 `-Last`
- `if (git show-ref --verify --quiet ...)` 在 PowerShell 里判断的是**命令输出**而非退出码 → 用 `$LASTEXITCODE`
- PowerShell 会把中文全角引号 `“”` 当成字符串定界符 → 长文本用 here-string `@'...'@`
- 传给原生命令的参数中若含双引号会被破坏 → SQL 走**文件 + 管道**，别用 `-e "..."`
- `mysql` 的密码警告会写 stderr；配合 `$ErrorActionPreference='Stop'` 会被当成终止错误 → 用 `$env:MYSQL_PWD`
- `git push` / `git fetch` 在受限沙箱下会报 `SEC_E_NO_CREDENTIALS`，需要放宽文件/凭据访问权限

### M1.0 实施中新踩到的坑（已实测，务必照做）

1. **`sensitive` 是 MySQL 8.0 保留字**：作为列名/别名时，**任何位置不加反引号都会 1064 语法错**，包括
   `SELECT ... sensitive FROM`、`INSERT INTO t (..., sensitive)` 和 `SELECT 0 AS sensitive`。
   本项目为 `ai_data_source_field` 选了这个列名，因此 SQL 与 mapper XML 里所有出现处都必须写成
   `` `sensitive` ``。（当时的症状极具误导性：报错位置会落在前一个 token 上，
   实测 `check the manual ... near 'sensitive, status)'` 其实指的是它前面的列名位置。
   DDL 里已加反引号，但 INSERT 列清单、SELECT 别名、`Base_Column_List` 都要逐一检查。）
2. **`mysql.exe` 在 Windows 上不读管道编码**：默认按 ANSI(cp936) 解释输入流，
   中文种子数据会变成 `\x90` 之类而报 `ERROR 1366 Incorrect string value`。
   必须显式加 `--default-character-set=utf8mb4`（与 `$OutputEncoding` 无关，已实测四条路径对比）。
   推荐调用方式（`<` 重定向在 PowerShell 里不可用）：
   ```powershell
   $env:MYSQL_PWD='<你的 MySQL 口令>'
   Get-Content -Raw -Encoding UTF8 script.sql |
     & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' `
       --host=127.0.0.1 --port=3306 --user=root --default-character-set=utf8mb4 db_name
   ```
3. **PowerShell 会破坏两类参数**（本会话实际踩到，命令会以假语法错失败）：
   - `-h127.0.0.1` → 被当成 `127.0.0.1` 的属性访问，实际传成 `127`，报 `Unknown MySQL server host '127'`
     → 用 `--host=127.0.0.1` 长选项形式。
   - `-Dfile.encoding=UTF-8`、`-Dsurefire.failIfNoSpecifiedTests=false` 这类**含点的 `-D` 参数**
     会被截断，报 `Unknown lifecycle phase ".failIfNoSpecifiedTests=false"` 或
     `ClassNotFoundException: /encoding=UTF-8` → **给整个参数加引号**：`'-Dsurefire.x=false'`。
     （注意：不含点的 `-DskipTests` / `-Dtest=...` 不受影响，所以这个坑只在特定参数上出现。）
4. **`mvn test` 跑不了测试是双重原因，不只是 `skipTests=true`**：
   - app 模块的 surefire 被 pom 钉在 **2.6**，而离线仓库里没有 `surefire-junit4:2.6`（只有 2.22.2）；
   - 该模块的 `skipTests` 是硬编码字面量，**不注入 user property**，所以命令行覆盖无效
     （其他模块由 parent 管理为 3.5.2，`-DskipTests=false` 是有效的——两个模块行为不一致）。
   - 若临时去掉 `<version>2.6</version>` 退回 parent 的 3.5.2，surefire 会选择
     **JUnitPlatformProvider**，而仓库里的测试是 JUnit 4（`org.junit.Test` + `SpringRunner`），
     离线仓库又**没有 `junit-vintage-engine``，于是结果是 `Tests run: 0`（静默不执行，不报错）。
   - 另外 `spring-boot-starter-test` **已在 app pom 里声明**（test scope），但离线仓库缺
     `junit-vintage-engine`，JUnit 4 用例经 surefire 3.5.2 的 JUnitPlatformProvider 会静默 `Tests run: 0`。
   - 结论：M1.0 期间**不要试图改 pom 来跑测试**。本次采用了不改仓库的验证路径：
     临时 patch pom → 让 surefire 把 `surefire.test.class.path` 写进
     `target/surefire-reports/TEST-*.xml` → 从该 classpath 中剔除 mockito
     （Mockito 5 的 inline mock maker 需要 JVM 自附加，**沙箱禁止自附加**）
     → 用 JUnit Platform Launcher 直接跑 → 立即用二进制备份把 pom 还原到逐字节一致（SHA256 校验）。
     测试工具链的正式修复建议放到 M1.4 一并做。
   - M1.1 起改用更简单的路径：用 JUnit 4 的 `JUnitCore` 直接跑 JUnit 4 用例
     （绕开 surefire 与 vintage 引擎），从 surefire 导出的 classpath 里剔除 mockito 即可。

### M1.1 实施中新踩到的坑（已实测）

5. **ES MCP 在受限沙箱下 `npx` 拉不起来，报 `npm error EPERM`**：
   `npx.cmd -y @awesome-ai/elasticsearch-mcp` 要把包下载进全局 npm 缓存
   （`C:\Users\...\npm-cache\_cacache`），而 workspace-write 沙箱只允许写 workspace，
   于是 `EPERM: operation not permitted, open ...npm-cache...`。
   **解决**：把 npm 缓存重定向到 workspace（可写区），并写入 MCP 的 `transport_config` env：
   ```sql
   UPDATE ai_client_tool_mcp
   SET transport_config = JSON_SET(transport_config,
       '$."elasticsearch-mcp-server".env.npm_config_cache',
       'D:\\rag_mcp_aiagent\\aiagent\\ai-agent-station-study\\.npm-cache-m1')
   WHERE mcp_id = '5007';
   ```
   该目录已加入 `.gitignore`（`/.npm-cache-m1/`），真实（非沙箱）环境不需要此覆盖。
6. **ES MCP 的 `search` 返回是平铺文本，不是 JSON**（`@awesome-ai/elasticsearch-mcp`）：
   `content[0]` = `Total search results: N, Displaying M records starting from position 0`，
   其余每段 = 一条命中，格式为 `field: value` 行；text 字段命中时以
   `field (Highlight): ...` 行替代并省略原始行。且**不返回 `_id`**。
   因此 `ElasticsearchProvider` 要解析文本格式，且 `rawRef` 拿不到 `_id`
   （M3 证据面板若要原文回指，需直连 ES 或换一个返回结构化结果的 MCP/接入方式）。



---

## 1. 目标与验收标准

**目标**：把「数据访问」从 Agent 执行链里抽出来，让 ES 只是**第一个 Provider**；同时对工具层做治理。

| # | 验收标准 | 怎么测 |
|---|---|---|
| 1 | 接第二个数据源（MySQL 订单只读）**不改 Agent 侧代码** | 只加配置 + 一个 Provider 实现类 |
| 2 | **四步链完整走完**（阶段1→2→3→回流→4） | 发一次 `aiAgentId=4` 排查请求，日志应出现 `阶段2`、`阶段3` |
| 3 | **只读白名单生效**：模型可见工具不含写/删类 | 打印注入的工具名列表 |
| 4 | 结果模型带 `rawRef` | 为 M3 证据面板预留，晚加要回头改所有 Provider |

## 2. 关键设计决策（**决定 M1 形态，需先确认**）

> **原始 ES MCP 的 11 个工具不再直接注入模型；模型只看到我们生成的语义工具。**

```
模型可见  →  query_logs(keyword, timeRange, source)      ← 语义工具
             query_orders(orderNo, timeRange)
                  ↓ 内部路由
            ElasticsearchProvider（走 MCP callTool）
            MysqlOrderProvider   （走 JdbcTemplate）
```

- **收益**：写/删工具自然消失（默认拒绝）、模型不必懂 ES DSL、换源对模型完全透明
- **代价**：失去模型自由探索索引的能力（`list_indices`/`get_mappings`），用 `describe_entity` 语义工具补回
- **备选**（若希望保留模型自由探索）：做"原始工具白名单"（按工具名过滤），复杂度更高且每个源都要维护白名单

## 3. 包结构与分层

| 放哪 | 内容 |
|---|---|
| `domain/agent/adapter/port/datasource/` | SPI 接口 |
| `domain/agent/adapter/repository/IDataSourceRepository` | 配置读取接口 |
| `domain/agent/model/valobj/datasource/` | `TimeRange` / `EntityQuery` / `LogSearchQuery` / `QueryResult` / `EntityMeta` |
| `domain/agent/service/datasource/DataSourceQueryService` | 门面：选源 → 注入 tenantId → 调用 → 记 trace |
| `domain/agent/service/datasource/tool/DataSourceToolCallbackFactory` | 生成语义 `ToolCallback`（按阶段裁剪） |
| `infrastructure/gateway/datasource/` | `ElasticsearchProvider` / `MysqlOrderProvider` |
| `infrastructure/dao/` + `app/resources/mybatis/mapper/` | 新表 DAO + XML |

> 注：本仓库的二级包是领域名 `agent`（如 `domain.agent.adapter.repository`），上述 domain 路径带 `agent`；
> 本文档早期版本的示意路径 `domain/adapter/...` 已更正。`infrastructure.gateway` 下无 `agent`（与仓库一致）。

> ES Provider 需要运行时的 `McpSyncClient`——它由 domain 的 `BeanRegistry` 按名注册（`ai_client_tool_mcp_<mcpId>`）。依赖方向 infrastructure → domain，所以 infrastructure 可注入 `BeanRegistry` 取句柄。

## 4. 数据模型（DDL）

```sql
-- 数据源实例
CREATE TABLE `ai_data_source` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` varchar(64) NOT NULL COMMENT '数据源ID',
  `ds_name` varchar(64) NOT NULL COMMENT '数据源名称',
  `ds_type` varchar(32) NOT NULL COMMENT '类型(mysql/elasticsearch/loki/clickhouse)',
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户标识',
  `mcp_id` varchar(64) DEFAULT NULL COMMENT '经MCP接入时对应的ai_client_tool_mcp.mcp_id',
  `conn_config` varchar(2048) DEFAULT NULL COMMENT '连接配置(JSON,不含明文密码)',
  `time_field` varchar(64) DEFAULT NULL COMMENT '默认时间字段',
  `max_rows` int NOT NULL DEFAULT 200 COMMENT '单次结果行数硬上限',
  `timeout_ms` int NOT NULL DEFAULT 5000 COMMENT '查询超时(毫秒)',
  `status` tinyint(1) DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_ds_id` (`ds_id`), KEY `idx_tenant_status` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据源配置表';

-- 实体（订单/日志/指标…）
CREATE TABLE `ai_data_source_entity` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ds_id` varchar(64) NOT NULL,
  `entity_code` varchar(64) NOT NULL COMMENT '实体编码(order/log)',
  `entity_name` varchar(64) NOT NULL COMMENT '实体名称',
  `entity_desc` varchar(512) DEFAULT NULL COMMENT '实体描述(注入给模型)',
  `physical_name` varchar(128) NOT NULL COMMENT '物理定位(表名/索引模式)',
  `capability` varchar(32) NOT NULL COMMENT '能力(entity_query/log_search)，均为只读',
  `query_template` text COMMENT '参数化查询模板(禁止模型拼查询语句)',
  `status` tinyint(1) DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_ds_entity` (`ds_id`,`entity_code`), KEY `idx_capability` (`capability`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据源实体配置表';

-- 字段字典
CREATE TABLE `ai_data_source_field` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ds_id` varchar(64) NOT NULL, `entity_code` varchar(64) NOT NULL,
  `field_name` varchar(64) NOT NULL, `field_label` varchar(64) DEFAULT NULL COMMENT '中文名',
  `field_type` varchar(32) DEFAULT NULL, `is_time_field` tinyint(1) DEFAULT '0',
  `is_filterable` tinyint(1) DEFAULT '1', `sample_value` varchar(256) DEFAULT NULL,
  `sensitive` tinyint(1) DEFAULT '0' COMMENT '是否需脱敏',
  `status` tinyint(1) DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_ds_entity_field` (`ds_id`,`entity_code`,`field_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据源字段字典表';

-- 【新增】阶段-工具能力策略：解决"四步链被短路"
CREATE TABLE `ai_stage_tool_policy` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stage_code` varchar(64) NOT NULL COMMENT '阶段=ai_agent_flow_config.client_type，如 TASK_ANALYZER_CLIENT',
  `capability` varchar(32) NOT NULL COMMENT '能力(entity_meta/log_search/entity_query)',
  `allowed` tinyint(1) DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_stage_capability` (`stage_code`,`capability`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='阶段-工具能力策略表';
```

**`ai_stage_tool_policy` 种子数据**（关键：分析/监督阶段不给执行类能力）

| stage_code | capability | allowed |
|---|---|---|
| `TASK_ANALYZER_CLIENT` | entity_meta | 1 |
| `TASK_ANALYZER_CLIENT` | log_search / entity_query | **0** |
| `PRECISION_EXECUTOR_CLIENT` | log_search / entity_query | 1 |
| `QUALITY_SUPERVISOR_CLIENT` | entity_meta | 1 |
| `QUALITY_SUPERVISOR_CLIENT` | log_search / entity_query | **0** |
| `RESPONSE_ASSISTANT` | 全部 | 0 |

**`ai_data_source` 种子数据（两个源）**

| ds_id | ds_type | 接入 | 说明 |
|---|---|---|---|
| `ds_log_es` | elasticsearch | `mcp_id = 5007` | 日志 |
| `ds_order_mysql` | mysql | `conn_config` = 只读 JDBC | 订单 |

> ⚠️ 四张新表都要加进 `AiClientConfigRefreshScheduler.queryConfigVersion()` 的 UNION，否则改配置不触发热更新（M0 修过的坑）。

## 5. 统一查询模型（明确"不做什么"）

**统一**：`时间窗` + `tenantId` + `分页/上限` + `排序` + `超时` + `结果条数硬上限`
**不统一**：查询语法本身 —— 各 Provider 用自己的 `query_template` / DSL 模板，**不做万能 DSL**

```java
public class TimeRange { Instant start; Instant end; }

public class EntityQuery {
    String entityCode;
    String tenantId;             // 由框架注入，绝不出现在工具 schema 里
    TimeRange timeRange;
    List<FieldFilter> filters;   // 结构化过滤，不是裸字符串
    List<String> selectFields;
    String orderBy; boolean desc;
    int limit;                   // 再被 provider.maxRows 硬截断
}

public class LogSearchQuery {
    String tenantId; TimeRange timeRange; String keyword;
    Map<String,String> termFilters; int limit;
}

public class QueryResult {
    String dsId; String entityCode;
    List<String> columns;
    List<Map<String,Object>> rows;   // 已裁剪、已脱敏
    long total; boolean truncated; long costMs;
    String rawRef;                   // 原文引用，给 UI；不进 Prompt
    List<String> warnings;
}
```

## 6. SPI 接口签名

```java
public interface IDataSourceProvider {
    String dsId();
    String dsType();
    Set<DataSourceCapability> capabilities();
    HealthStatus healthCheck();
    List<EntityMeta> listEntities();
}

public interface IEntityQueryProvider extends IDataSourceProvider, AutoCloseable {
    QueryResult query(EntityQuery query);
}

public interface ILogSearchProvider extends IDataSourceProvider, AutoCloseable {
    QueryResult search(LogSearchQuery query);
}

public interface IDataSourceRegistry {
    void register(IDataSourceProvider provider);
    void unregister(String dsId);
    Optional<IDataSourceProvider> get(String dsId);
    List<IDataSourceProvider> listByTenant(String tenantId);
}

public enum DataSourceCapability { ENTITY_META, ENTITY_QUERY, LOG_SEARCH }
```

**三条纪律**
1. `tenantId` **不作为模型参数**，由 `DataSourceQueryService` 从会话上下文注入
2. `rawRef` 从第一天就返回（M3 证据面板要用）
3. 每个 Provider 自带上限与超时，不靠调用方自觉

## 7. Provider 实现要点

**`ElasticsearchProvider`**
- 从 `ai_data_source.mcp_id` 取 `mcpId`，用 `BeanRegistry.getBean("ai_client_tool_mcp_" + mcpId)` 拿 `McpSyncClient`
- 调 MCP 的 `search` 工具（`queryBody` 由 `query_template` 渲染，**不由模型提供**）
- 返回前：按 `ai_data_source_field` 裁剪字段、`sensitive=1` 脱敏、`max_rows` 截断

**`MysqlOrderProvider`**
- 连接池照抄 `DataSourceConfig` 里 `pgVectorDataSource` 的写法，但**不要**照抄 `setInitializationFailTimeout(1)`（业务库启动不可用不该让应用起不来）
- 只读账号 + `ds.setReadOnly(true)` + 只读副本
- SQL 只来自 `query_template` 占位符替换，值走 `PreparedStatement` 参数绑定
- `queryTimeout = timeout_ms`；`LIMIT = min(请求值, max_rows)`
- 用 `JdbcTemplate` 就够，**不要**给它配 MyBatis

**工具暴露**：`DataSourceToolCallbackFactory` 把 `(capability, entity)` 转成 `ToolCallback`，schema 由 `ai_data_source_field` 生成，工具名用语义名（`query_logs` / `query_orders` / `describe_entity`）。

## 8. 接入点：要改哪些现有文件

| 文件 | 改动 |
|---|---|
| `AiClientNode` | 工具来源从 `SyncMcpToolCallbackProvider(mcpSyncClients)` 换成 `DataSourceToolCallbackFactory.buildFor(clientId, clientType)`；**保留**已有的 `containsBean` 容错 |
| `AiClientModelNode` | **移除模型级工具注入**（`defaultOptions().toolCallbacks(...)`）—— 这是"四个客户端都拿到工具"的根因 |
| `AiClientToolMcpNode` | **不改**：仍创建 `McpSyncClient` 并注册 Bean；只是其工具不再直接给模型 |
| `AiClientConfigRefreshScheduler` | `queryConfigVersion()` 的 UNION 加入 4 张新表 |
| 新增 | `DataSourceQueryService`、两个 Provider、`DataSourceToolCallbackFactory`、3~4 个 DAO + mapper XML |

## 9. 落地顺序（每步独立可验证）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M1.0** | 4 张表 + DAO/XML + SPI + `DataSourceRegistry` + 种子数据，**零行为变更** | 编译通过、原有回归不变 |
| **M1.1** | `ElasticsearchProvider` + `query_logs`；`AiClientNode`/`AiClientModelNode` 改为只注入语义工具 | 排查请求仍能命中 ES 真实数据 |
| **M1.2** | 阶段裁剪生效 | **四步链完整**（日志出现 `阶段2`、`阶段3`）；写/删工具不可见 |
| **M1.3** | `MysqlOrderProvider`（第二源） | 仅加配置 + 1 个类，Agent 侧零改动 |
| **M1.4** | 单测 + 文档 + PR | **反例验证**：把 stage policy 改回全允许，四步链应再次被短路 |

> M1.2 必须复用「反例验证」方法（本项目已验证有效）：先证明问题存在，再证明修复有效。这样"工具按阶段裁剪"才有实测支撑。

## 10. 明确不做的（留给后续）

- BM25 + RRF 混合检索、rerank → 需要 embedding 服务（当前只有 DeepSeek key）
- 多租户落库与全链路隔离 → M5
- 会话级版本快照 → M6
- Tool Call 事件结构化（SSE）与 UI → M3 / 独立 UI 分支
- 跨源关联（日志时间线 ↔ 订单时间线对齐）→ M1.3 之后单独做

---

## 附：待确认的两个决策

1. 是否接受第 2 节的「原始 ES 工具不再暴露、只暴露语义工具」？（**推荐接受**）
2. `ai_stage_tool_policy` 落库 vs 代码内静态映射？（**推荐落库**，符合"配置驱动"卖点）
