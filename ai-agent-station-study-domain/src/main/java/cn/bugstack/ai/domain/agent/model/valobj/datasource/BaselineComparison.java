package cn.bugstack.ai.domain.agent.model.valobj.datasource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单段对比（M8）：故障期或某一侧正常期
 * <p>
 * {@code phase} 是给模型与前端看的**唯一语义标签**：{@code fault} / {@code baseline_before} / {@code baseline_after}。
 * 它只描述"这段数据属于哪个阶段"，**不描述因果**。
 *
 * @author miaowazhongzi
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BaselineComparison {

    public static final String PHASE_FAULT = "fault";

    public static final String PHASE_BASELINE_BEFORE = "baseline_before";

    public static final String PHASE_BASELINE_AFTER = "baseline_after";

    /**
     * 阶段（fault / baseline_before / baseline_after）
     */
    private String phase;

    /**
     * 该阶段的时间窗
     */
    private TimeRange range;

    /**
     * 降级说明（非空时进 warnings），例如"前基线按故障窗长度镜像构造"
     */
    private String note;

    /**
     * 排序权重：故障期在前，其次前基线、后基线
     */
    public int phaseOrder() {
        if (PHASE_FAULT.equals(phase)) {
            return 0;
        }
        if (PHASE_BASELINE_BEFORE.equals(phase)) {
            return 1;
        }
        return PHASE_BASELINE_AFTER.equals(phase) ? 2 : 9;
    }

}
