package cn.bugstack.ai.domain.agent.model.valobj.datasource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 日志检索查询，值对象
 * <p>
 * 日志场景天然是"关键字 + 时间窗 + 若干精确过滤"，与实体结构化查询是两种不同的形状，
 * 因此单独建模而不强行合并成一个"万能查询"对象（设计文档第 5 节：统一时间窗与上限，不统一查询语法）。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LogSearchQuery {

    /**
     * 租户标识（框架注入，非模型参数）
     */
    private String tenantId;

    /**
     * 时间窗
     */
    private TimeRange timeRange;

    /**
     * 检索关键字
     */
    private String keyword;

    /**
     * 精确过滤（如 level=ERROR、service_name=xxx）
     */
    @Builder.Default
    private Map<String, String> termFilters = new HashMap<>();

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
