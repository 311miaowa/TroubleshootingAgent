package cn.bugstack.ai.domain.agent.service.datasource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * 行内时间值的归一化工具（P1 跨源关联的地基）
 * <p>
 * <b>为什么需要它</b>：跨源对齐的第一步是"两侧的时间能不能比"。而两侧的时间在代码里长得完全不一样：
 * <ul>
 *     <li><b>ES 日志</b>：{@code @timestamp} 是<b>带时区的字符串</b>，如 {@code 2026-03-27T10:30:00+08:00}；</li>
 *     <li><b>MySQL 订单</b>：{@code create_time} 是 {@code datetime}（<b>无时区</b>），
 *         JDBC 取出来是 {@link Timestamp}，序列化成 JSON 后又变成 <b>epoch 毫秒</b>（如 {@code 1774578600000}）。</li>
 * </ul>
 * 不先把它们归一到同一个 {@link Instant}，就会写出"看起来能跑、一比就错 8 小时"的关联逻辑——
 * 所以这里把"能解析的形态"全部列出来并配单测钉住。
 * <p>
 * <b>时区约定</b>：无时区的时间值（{@code create_time}、裸字符串）一律按 {@link #BIZ_ZONE}
 * （Asia/Shanghai，与订单库 {@code serverTimezone=Asia/Shanghai} 及日志里的 {@code +08:00} 一致）解释。
 * <p>
 * <b>取值纪律</b>：解析不出来就返回 {@code null}，由调用方记录告警并跳过该行——**不猜、不补默认时间**。
 *
 * @author miaowazhongzi
 */
public final class RowTimes {

    /**
     * 业务时区：订单库 serverTimezone、日志 @timestamp 的偏移都按它对齐
     */
    public static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * epoch 数值的"秒 / 毫秒"分界：≥ 1e11 视为毫秒（1e11 秒 ≈ 公元 5138 年，不可能出现在秒级时间戳里）
     */
    private static final long EPOCH_MILLIS_THRESHOLD = 100_000_000_000L;

    private static final DateTimeFormatter SPACE_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter SPACE_DATETIME_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final DateTimeFormatter SPACE_MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private RowTimes() {
    }

    /**
     * 把一行里的时间字段值解析成 {@link Instant}
     *
     * @param value 支持 {@link Instant} / {@link OffsetDateTime} / {@link ZonedDateTime} /
     *              {@link LocalDateTime} / {@link Timestamp} / {@link Date} / {@link Number} /
     *              常见时间字符串
     * @return 解析结果；无法识别返回 {@code null}（调用方负责告警与跳过）
     */
    public static Instant parse(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(BIZ_ZONE).toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Number number) {
            return fromEpoch(number.longValue());
        }
        if (value instanceof CharSequence text) {
            return parseText(text.toString());
        }
        return null;
    }

    /**
     * epoch 数值 → Instant：按量级判断秒还是毫秒
     */
    public static Instant fromEpoch(long epoch) {
        if (epoch <= 0L) {
            return null;
        }
        return epoch >= EPOCH_MILLIS_THRESHOLD ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
    }

    /**
     * 字符串 → Instant：依次尝试带偏移的 ISO、UTC 的 ISO、裸日期时间（按业务时区）
     */
    public static Instant parseText(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }

        // 1) 带偏移：2026-03-27T10:30:00+08:00 / 2026-03-27T02:30:00Z
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它形态
        }

        // 2) 裸 ISO 日期时间：2026-03-27T10:30:00（按业务时区）
        try {
            return LocalDateTime.parse(value).atZone(BIZ_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续
        }

        // 3) 数据库风格：2026-03-27 10:30:00[.SSS] / 2026-03-27 10:30
        for (DateTimeFormatter formatter : new DateTimeFormatter[]{SPACE_DATETIME_MILLIS, SPACE_DATETIME, SPACE_MINUTE}) {
            try {
                return LocalDateTime.parse(value, formatter).atZone(BIZ_ZONE).toInstant();
            } catch (DateTimeParseException ignored) {
                // 试下一个格式
            }
        }

        // 4) 纯数字字符串（如 "1774578600000"）
        try {
            return fromEpoch(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Instant → 业务时区的"墙上时间"文本（{@code yyyy-MM-dd HH:mm:ss}），用于给人看的时间桶标签
     */
    public static String formatBucket(Instant instant) {
        return instant == null ? null : SPACE_DATETIME.format(instant.atZone(BIZ_ZONE));
    }

}
