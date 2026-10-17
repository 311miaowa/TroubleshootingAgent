package cn.bugstack.ai.domain.agent.model.valobj.datasource;

import cn.bugstack.ai.domain.agent.service.datasource.RowTimes;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 故障窗与对称正常基线（M8）
 * <p>
 * <b>为什么要有它</b>：只有故障期数据时，"限流 WARN 出现 20 次"是多是少根本没法判断——缺正常期参照，
 * 异常就无法判定。本值对象把"故障窗 + 两侧正常期（对称基线）"算清楚，交给对比服务去查数。
 * <p>
 * <b>三条硬约束（M8 决策 2/3/4）</b>：
 * <ol>
 *     <li><b>避开故障余波</b>：前基线的结束 = 故障开始 − {@code offset}（默认 10 分钟），**绝不等于故障开始**；</li>
 *     <li><b>不猜</b>：没有上次故障结束时间时，前基线按"故障窗长度镜像"构造，<b>并在 warnings 写明</b>；
 *         没有下次故障开始时间时**不构造后基线**，同样写明；</li>
 *     <li><b>参数不进 schema</b>：{@code offset}、最小基线长度都是本类常量，模型看不到也改不了。</li>
 * </ol>
 * <p>
 * <b>纯函数</b>：只做时间运算，不发请求、不读配置、不依赖时钟（{@code now} 由调用方传入），因此可完整单测。
 *
 * @author miaowazhongzi
 */
@Getter
@Builder
public class FaultWindow {

    /**
     * 动态上下文 / ToolContext 里的键（一次请求一个故障窗）
     */
    public static final String CONTEXT_KEY = "_m8_fault_window";

    /**
     * 故障余波偏移（分钟）：基线要离故障开始这么远，避免把"故障刚开始的爬升段"算进基线
     */
    public static final int DEFAULT_OFFSET_MINUTES = 10;

    /**
     * 基线最短长度（分钟）：过短不产出对比（否则基线只有几条数据，反而误导）
     */
    public static final int MIN_BASELINE_MINUTES = 5;

    /**
     * 故障开始（必填，否则不构成故障窗）
     */
    private final Instant start;

    /**
     * 故障结束；为 null 表示"故障进行中"，按 {@code now} 推导（并在 warnings 写明）
     */
    private final Instant end;

    /**
     * 上次故障结束时间（可为 null → 前基线镜像构造）
     */
    private final Instant prevFaultEnd;

    /**
     * 下次故障开始时间（可为 null → 不构造后基线）
     */
    private final Instant nextFaultStart;

    /**
     * 故障标签（可选，给人看、进提示词上下文块；不参与查询）
     */
    private final String label;

    @Builder.Default
    private final int offsetMinutes = DEFAULT_OFFSET_MINUTES;

    /**
     * 解析期产生的告警（字段无法解析等），与运行期降级告警合并输出
     */
    @Builder.Default
    private final List<String> parseWarnings = List.of();

    /**
     * 从请求字段解析；{@code faultStart} 缺失或无法解析 → 返回 null（等于"本次没有故障窗"，行为回到旧逻辑）
     */
    public static FaultWindow parse(String faultStart, String faultEnd, String prevFaultEnd,
                                    String nextFaultStart, String label) {
        List<String> notes = new ArrayList<>();
        Instant start = parseOne("faultStart", faultStart, notes);
        if (start == null) {
            return null;
        }
        return FaultWindow.builder()
                .start(start)
                .end(parseOne("faultEnd", faultEnd, notes))
                .prevFaultEnd(parseOne("prevFaultEnd", prevFaultEnd, notes))
                .nextFaultStart(parseOne("nextFaultStart", nextFaultStart, notes))
                .label(label)
                .offsetMinutes(DEFAULT_OFFSET_MINUTES)
                .parseWarnings(List.copyOf(notes))
                .build();
    }

    /**
     * 单字段解析：沿用 {@link RowTimes#parseText(String)} 的口径（带偏移 ISO / 裸 ISO 按 Asia/Shanghai / epoch）
     */
    private static Instant parseOne(String field, String text, List<String> notes) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Instant at = RowTimes.parseText(text);
        if (at == null) {
            notes.add(field + " 无法解析（期望 ISO-8601，如 2026-03-27T10:00:00+08:00），已忽略");
        }
        return at;
    }

    /**
     * 是否构成可用故障窗（只要求开始时间）
     */
    public boolean isUsable() {
        return start != null;
    }

    /**
     * 故障窗是否"进行中"（未给结束时间）
     */
    public boolean isOngoing() {
        return end == null;
    }

    /**
     * 故障结束时间：未给则取 {@code now}（进行中）
     */
    public Instant endOrNow(Instant now) {
        if (end == null) {
            return now;
        }
        // 结束早于开始 → 视为零长度（并在 warnings 说明），不让下游算出负长度窗口
        return end.isBefore(start) ? start : end;
    }

    /**
     * 故障期时间窗
     */
    public TimeRange faultRange(Instant now) {
        return TimeRange.of(start, endOrNow(now));
    }

    /**
     * 前基线（对称正常期，且避开余波）
     * <ul>
     *     <li>有上次故障结束：{@code [prevFaultEnd + offset, faultStart − offset)}</li>
     *     <li>无上次故障结束：{@code [faultStart − 故障长度 − offset, faultStart − offset)}（镜像，warnings 写明）</li>
     * </ul>
     *
     * @return 空表示"基线不可用"（过短 / 起点晚于终点），调用方据此写 warnings
     */
    public Optional<TimeRange> baselineBefore(Instant now) {
        Instant baselineEnd = start.minus(Duration.ofMinutes(offsetMinutes));
        Instant baselineStart = prevFaultEnd != null
                ? prevFaultEnd.plus(Duration.ofMinutes(offsetMinutes))
                : baselineEnd.minus(faultLength(now));
        if (!baselineStart.isBefore(baselineEnd)) {
            return Optional.empty();
        }
        if (Duration.between(baselineStart, baselineEnd).toMinutes() < MIN_BASELINE_MINUTES) {
            return Optional.empty();
        }
        return Optional.of(TimeRange.of(baselineStart, baselineEnd));
    }

    /**
     * 后基线：**只有**给出下次故障开始时间才构造（不猜）
     */
    public Optional<TimeRange> baselineAfter(Instant now) {
        if (nextFaultStart == null) {
            return Optional.empty();
        }
        Instant baselineStart = endOrNow(now).plus(Duration.ofMinutes(offsetMinutes));
        if (!baselineStart.isBefore(nextFaultStart)) {
            return Optional.empty();
        }
        if (Duration.between(baselineStart, nextFaultStart).toMinutes() < MIN_BASELINE_MINUTES) {
            return Optional.empty();
        }
        return Optional.of(TimeRange.of(baselineStart, nextFaultStart));
    }

    /**
     * 故障期 + 两侧基线（按 phase 排序：fault → baseline_before → baseline_after），每项带降级说明
     */
    public List<BaselineComparison> comparisons(Instant now) {
        List<BaselineComparison> list = new ArrayList<>(3);
        list.add(BaselineComparison.builder()
                .phase(BaselineComparison.PHASE_FAULT)
                .range(faultRange(now))
                .note(isOngoing() ? "故障未给结束时间，按当前时间（now）作为结束" : null)
                .build());
        baselineBefore(now).ifPresent(range -> list.add(BaselineComparison.builder()
                .phase(BaselineComparison.PHASE_BASELINE_BEFORE)
                .range(range)
                .note(prevFaultEnd == null ? "未提供上次故障时间，前基线按故障窗长度镜像构造" : null)
                .build()));
        baselineAfter(now).ifPresent(range -> list.add(BaselineComparison.builder()
                .phase(BaselineComparison.PHASE_BASELINE_AFTER)
                .range(range)
                .note(null)
                .build()));
        return list;
    }

    /**
     * 运行期降级告警（解析告警 + 四种降级的说明），全部要被调用方写进 {@code QueryResult.warnings}
     */
    public List<String> warnings(Instant now) {
        List<String> list = new ArrayList<>(parseWarnings);
        if (isOngoing()) {
            list.add("故障未给结束时间，故障期按 [开始, 当前时间) 统计");
        }
        if (end != null && end.isBefore(start)) {
            list.add("faultEnd 早于 faultStart，已按零长度故障窗处理");
        }
        if (baselineBefore(now).isEmpty()) {
            list.add(prevFaultEnd == null
                    ? "前基线不可用（镜像窗口过短或与故障窗重叠），未产出前基线对比"
                    : "前基线不可用（上次故障结束 + " + offsetMinutes + "min 晚于故障开始 − " + offsetMinutes
                            + "min，或窗口短于 " + MIN_BASELINE_MINUTES + " 分钟），未产出前基线对比");
        } else if (prevFaultEnd == null) {
            // M8 §4 硬要求：镜像构造**必须**在 warnings 写明，不能静默
            list.add("未提供上次故障时间，前基线按故障窗长度镜像构造");
        }
        if (baselineAfter(now).isEmpty()) {
            list.add(nextFaultStart == null
                    ? "未提供下次故障开始时间，未构造后基线（不猜）"
                    : "后基线不可用（长度不足 " + MIN_BASELINE_MINUTES + " 分钟或与故障窗重叠），未产出后基线对比");
        }
        return list;
    }

    /**
     * 给提示词上下文块用的单行描述（机械事实，不含任何判断）
     */
    public String describe(Instant now) {
        StringBuilder sb = new StringBuilder();
        sb.append("故障窗：").append(RowTimes.formatBucket(start)).append(" ~ ")
                .append(RowTimes.formatBucket(endOrNow(now)))
                .append(isOngoing() ? "（进行中）" : "")
                .append(offsetMinutes).append(" 分钟余波偏移");
        if (label != null && !label.isBlank()) {
            sb.append("；标签：").append(label);
        }
        baselineBefore(now).ifPresent(r -> sb.append("；前基线：")
                .append(RowTimes.formatBucket(r.getStart())).append(" ~ ").append(RowTimes.formatBucket(r.getEnd())));
        baselineAfter(now).ifPresent(r -> sb.append("；后基线：")
                .append(RowTimes.formatBucket(r.getStart())).append(" ~ ").append(RowTimes.formatBucket(r.getEnd())));
        return sb.toString();
    }

    private Duration faultLength(Instant now) {
        Duration d = Duration.between(start, endOrNow(now));
        return d.isNegative() || d.isZero() ? Duration.ofMinutes(MIN_BASELINE_MINUTES) : d;
    }

    /**
     * 按 phase 的稳定排序（便于输出可预期）
     */
    public static Comparator<BaselineComparison> phaseOrder() {
        return Comparator.comparingInt(BaselineComparison::phaseOrder);
    }

}
