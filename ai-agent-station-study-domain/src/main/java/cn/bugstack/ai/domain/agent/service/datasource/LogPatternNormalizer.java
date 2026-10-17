package cn.bugstack.ai.domain.agent.service.datasource;

import java.util.regex.Pattern;

/**
 * 日志模板归一（M7a）
 * <p>
 * <b>它解决什么</b>：一次日志检索返回的几百行里，多数是"同一类内容的不同实例"——
 * 时间戳、UUID、IP、纯数字各不相同，但说的是同一件事。不先把这些易变部分抹掉，
 * "同一类异常出现了 37 次"这个事实就统计不出来。
 * <p>
 * <b>边界（有意为之）</b>：
 * <ul>
 *     <li><b>无状态、纯函数</b>：不训练、不持久化、不需要正常期标注数据；</li>
 *     <li><b>可解释</b>：归并规则就是下面 5 条正则，出问题能一眼看出归错了哪一类；</li>
 *     <li><b>已知代价</b>：会把"超时 3000ms"与"超时 5000ms"归为一类（<b>这是要的</b>），
 *         也会把不同错误码归为一类（<b>这是损失</b>）。接受它换无状态与可解释。</li>
 * </ul>
 * <p>
 * <b>纪律</b>：本类不做任何因果判断，也不改大小写——level 与错误码的大小写是有意义的。
 *
 * @author miaowazhongzi
 */
public final class LogPatternNormalizer {

    /**
     * 归一后模板的最大长度（超出截断），避免把原始长消息整段塞回模型
     */
    public static final int MAX_PATTERN_LENGTH = 200;

    /**
     * 无法归一时的占位模板
     */
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * 时间戳：{@code 2026-03-27T10:30:00.123+08:00} / {@code 2026-03-27 10:30:00} / 带 Z
     */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?");

    /**
     * UUID / 长十六进制串（含 traceId、spanId 这类 8 位以上 hex）
     */
    private static final Pattern UUID_OR_HEX = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
                    + "|\\b(?:0x)?[0-9a-fA-F]{8,}\\b");

    /**
     * IPv4（含端口不匹配，端口由数字规则处理）
     */
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");

    /**
     * 数字（含小数、负数由前导 '-' 保留）
     */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private LogPatternNormalizer() {
    }

    /**
     * 把一行原始内容归一成模板文本
     *
     * @param raw 原始行内容（通常取 message 字段）；为 {@code null}/空白时返回 {@link #UNKNOWN}
     * @return 归一化模板（≤ {@link #MAX_PATTERN_LENGTH} 字符）
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return UNKNOWN;
        }
        // 顺序敏感：时间戳 → UUID/长 hex → IP → 数字（先替长的，否则数字规则会把时间戳切碎）
        text = TIMESTAMP.matcher(text).replaceAll("<ts>");
        text = UUID_OR_HEX.matcher(text).replaceAll("<hex>");
        text = IPV4.matcher(text).replaceAll("<ip>");
        text = NUMBER.matcher(text).replaceAll("<num>");
        text = SPACES.matcher(text).replaceAll(" ").trim();
        if (text.isEmpty()) {
            return UNKNOWN;
        }
        return text.length() > MAX_PATTERN_LENGTH ? text.substring(0, MAX_PATTERN_LENGTH) : text;
    }

}
