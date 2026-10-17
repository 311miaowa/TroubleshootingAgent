package cn.bugstack.ai.domain.agent.model.valobj.datasource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 统一时间窗，值对象
 * <p>
 * 所有数据源共用同一套时间窗表达，各 Provider 自行翻译成自己的查询语法。
 * <p>
 * 说明：当前用 {@link Instant}（UTC 瞬时）。而数据库里的 create_time 是 {@code datetime}
 * （无时区）——两者的时区语义对齐在 M1.3 接 MySQL 订单源时统一处理，本步只定义类型。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TimeRange {

    /**
     * 起始时间（含）
     */
    private Instant start;

    /**
     * 结束时间（含）
     */
    private Instant end;

    /**
     * 最近 N 分钟
     */
    public static TimeRange lastMinutes(int minutes) {
        Instant end = Instant.now();
        return TimeRange.builder()
                .start(end.minus(minutes, ChronoUnit.MINUTES))
                .end(end)
                .build();
    }

    /**
     * 最近 N 小时
     */
    public static TimeRange lastHours(int hours) {
        Instant end = Instant.now();
        return TimeRange.builder()
                .start(end.minus(hours, ChronoUnit.HOURS))
                .end(end)
                .build();
    }

    /**
     * 显式时间窗
     */
    public static TimeRange of(Instant start, Instant end) {
        return TimeRange.builder().start(start).end(end).build();
    }

    /**
     * 默认时间窗：最近 1 小时。
     * <p>
     * 由 Provider 在"未显式指定时间窗"时兜底，避免出现无界查询——
     * 无界查询既是性能风险，也是只读防线之外最容易出事的一类调用。
     */
    public static TimeRange defaultRange() {
        return lastHours(1);
    }

    /**
     * 是否缺少任一端点
     */
    public boolean isUnbounded() {
        return start == null || end == null;
    }

}
