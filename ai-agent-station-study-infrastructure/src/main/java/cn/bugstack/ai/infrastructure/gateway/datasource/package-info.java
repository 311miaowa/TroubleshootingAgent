/**
 * 数据源网关包（infrastructure 侧）
 * <p>
 * 放数据源的 Provider 实现：{@code ElasticsearchProvider}（M1.1）、{@code MysqlOrderProvider}（M1.3）。
 * 依赖方向 infrastructure → domain，因此本包实现 domain 的 SPI 接口，
 * 并可通过 domain 的 {@code BeanRegistry} 取运行时的 {@code McpSyncClient} 句柄。
 *
 * @author miaowazhongzi
 */
package cn.bugstack.ai.infrastructure.gateway.datasource;
