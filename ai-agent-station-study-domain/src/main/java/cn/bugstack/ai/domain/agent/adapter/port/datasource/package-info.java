/**
 * 统一数据源 SPI 端口包
 * <p>
 * 本包只放"接口与枚举"，不放值对象（值对象在
 * {@code cn.bugstack.ai.domain.agent.model.valobj.datasource}）。
 * <p>
 * 依赖方向：infrastructure 实现本包接口；domain 内部只依赖本包，不依赖任何具体 Provider。
 *
 * @author miaowazhongzi
 */
package cn.bugstack.ai.domain.agent.adapter.port.datasource;
