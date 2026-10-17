package cn.bugstack.ai.domain.agent.model.valobj.datasource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体结构化查询，值对象
 * <p>
 * <b>纪律</b>：{@code tenantId} 由框架（{@code DataSourceQueryService}）从会话上下文注入，
 * 绝不出现在暴露给模型的工具 schema 里。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EntityQuery {

    /**
     * 通用过滤占位符（P1-2）：{@code query_template} 里出现它，才允许按字段字典过滤。
     * <p>
     * 放在 domain 是因为"哪些能力对模型可见"要由 domain 决定（工具 schema 是否暴露 filters），
     * 而 SQL 渲染在 infrastructure——常量定义在 domain，两边都引用它，避免 domain 反向依赖 infrastructure。
     */
    public static final String FILTERS_PLACEHOLDER = "${filters}";

    /**
     * 实体编码（如 order）
     */
    private String entityCode;

    /**
     * 租户标识（框架注入，非模型参数）
     */
    private String tenantId;

    /**
     * 时间窗
     */
    private TimeRange timeRange;

    /**
     * 订单号精确过滤（可选；为空/空白表示不过滤）。
     * <p>
     * M3.2 接线：仅 order 类实体使用，经 query_template 的 {@code ${orderNo}} 占位符绑定，
     * 值走 PreparedStatement 参数绑定，禁止模型拼 SQL。
     */
    private String orderNo;

    /**
     * 结构化过滤条件
     */
    @Builder.Default
    private List<FieldFilter> filters = new ArrayList<>();

    /**
     * 需要返回的字段；为空表示按字典返回全部非敏感字段
     */
    private List<String> selectFields;

    /**
     * 排序字段
     */
    private String orderBy;

    /**
     * 是否倒序
     */
    private boolean desc;

    /**
     * 期望返回行数；最终会被 {@code ai_data_source.max_rows} 再次硬截断
     */
    private int limit;

    /**
     * 时间窗兜底：无界查询一律收敛为默认窗（最近 1 小时）
     */
    public TimeRange timeRangeOrDefault() {
        return (timeRange == null || timeRange.isUnbounded()) ? TimeRange.defaultRange() : timeRange;
    }

}
