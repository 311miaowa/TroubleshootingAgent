package cn.bugstack.ai.domain.agent.adapter.port.datasource;

import java.util.Locale;

/**
 * 数据源能力枚举 —— **能力的唯一真源**（P2 收敛：此前工厂里还有一份字符串常量清单）
 * <p>
 * 语义边界：
 * <ul>
 *     <li>{@link #ENTITY_META} —— "有哪些实体/字段"，只读元数据，可安全地暴露给所有阶段；</li>
 *     <li>{@link #ENTITY_QUERY} —— 结构化查实体数据（订单/支付流水等），属执行类能力；</li>
 *     <li>{@link #LOG_SEARCH} —— 关键字检索日志，属执行类能力；</li>
 *     <li>{@link #ANOMALY_SUMMARY} —— 把原始日志行归纳成现象统计（M7a）；复用 {@link #LOG_SEARCH} 的源；</li>
 *     <li>{@link #CROSS_SOURCE} —— 跨源时间轴对齐（P1：日志 × 订单/支付）；</li>
 *     <li>{@link #FAULT_WINDOW} —— 故障期 vs 对称正常基线对比（M8）；窗口由请求侧提供。</li>
 * </ul>
 * <b>写/删类能力刻意不存在于此枚举中</b>：M1 的模型可见工具集只能由本枚举派生，
 * 因此"模型看到写/删工具"在类型层面就无法表达，而不是靠运行时过滤兜底。
 * <p>
 * <b>{@link #key()} 是协议值</b>：{@code ai_stage_tool_policy.capability} 列与
 * {@code EntityMeta.capability} 里存的就是它（小写），改枚举名等于改数据库契约，需配迁移。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
public enum DataSourceCapability {

    /**
     * 实体元数据（描述型，只读）
     */
    ENTITY_META,

    /**
     * 实体结构化查询（只读）
     */
    ENTITY_QUERY,

    /**
     * 日志检索（只读）
     */
    LOG_SEARCH,

    /**
     * 现象归纳（只读，M7a）：原始日志行 → 模板归一 + 计数/占比 + 桶分布 + 离群判定。
     * <p>
     * 与 {@link #LOG_SEARCH} 的区别：后者给"原始行"，本能力给"统计结论"。
     * 它需要的是<b>同一个日志源</b>，因此不新增 Provider 类型——由 {@code AnomalySummarizationService}
     * 复用 {@link #LOG_SEARCH} 的数据源实现。
     */
    ANOMALY_SUMMARY,

    /**
     * 跨源关联（只读，P1）：把日志时间线与订单/支付时间线对齐到同一根时间轴。
     * <p>
     * 它不绑定单一 Provider，而是由 {@code CrossSourceCorrelationService} 组合多个源；
     * 只要"至少一个源可查"就挂出该工具（避免在空环境里给模型一个永远用不上的工具）。
     */
    CROSS_SOURCE,

    /**
     * 故障期/基线对比（只读，M8）：把故障期与对称正常基线放在一起对比。
     * <p>
     * 故障窗来自请求侧（经 {@code ToolContext} 注入，**不进 schema**）；同样复用日志源实现。
     */
    FAULT_WINDOW;

    /**
     * 协议值：写进 {@code ai_stage_tool_policy.capability} 与 {@code EntityMeta.capability} 的小写串。
     */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

}
