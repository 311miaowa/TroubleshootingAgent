package cn.bugstack.ai.domain.agent.model.valobj.datasource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据源健康状态，值对象
 * <p>
 * 设计文档第 6 节的 {@code IDataSourceProvider#healthCheck()} 以此为返回值，
 * 但第 5 节的值对象清单里没有列出它。本类为补齐接口签名所必需。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HealthStatus {

    /**
     * 是否健康
     */
    private boolean healthy;

    /**
     * 说明（不健康时记录原因，如"mcp 未注册""连接超时"）
     */
    private String message;

    /**
     * 检查耗时（毫秒）
     */
    private long costMs;

    public static HealthStatus up() {
        return HealthStatus.builder().healthy(true).build();
    }

    public static HealthStatus down(String message) {
        return HealthStatus.builder().healthy(false).message(message).build();
    }

    /**
     * 不健康状态：在 message 末尾补上异常链中最深一层的原因
     * <p>
     * 动机（M4 实测）：缺密码时 Hikari 只抛出一句泛化的 {@code Failed to obtain JDBC Connection}，
     * 真正的原因（{@code Access denied for user 'ai_ro'@'localhost' (using password: NO)}）藏在
     * cause 链里。不还原它，日志就只能说"连不上"，无法定位。
     * <p>
     * <b>纪律</b>：只取异常自身的 message，不拼接 conn_config / 连接串 / 密码。
     */
    public static HealthStatus down(String message, Throwable cause) {
        String root = rootMessage(cause);
        String full = (root == null || root.isBlank() || (message != null && message.contains(root)))
                ? message
                : message + "（根因：" + root + "）";
        return HealthStatus.builder().healthy(false).message(full).build();
    }

    /**
     * 异常链中最深一层的非空 message（找不到就返回最外层 message）
     */
    private static String rootMessage(Throwable cause) {
        if (cause == null) {
            return null;
        }
        String message = cause.getMessage();
        Throwable current = cause.getCause();
        // 防御自引用/超长链
        int guard = 0;
        while (current != null && guard++ < 16) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message;
    }

}
