package cn.bugstack.ai.trigger.http.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 最小鉴权：对 {@code /api/**} 强制校验**共享口令**（GAP §3 P2「健康端点暴露面」收口）
 * <p>
 * <b>为什么不是 Spring Security</b>：本项目只有一个进程、一组内部接口，引入完整安全框架
 * （过滤器链、登录页、CSRF、会话）收益远小于复杂度。这里只做一件事：
 * 把"谁都能调"变成"知道口令才能调"。
 * <p>
 * <b>口令怎么传</b>：{@code X-API-Key: <key>} 或 {@code Authorization: Bearer <key>}（二选一）。
 * <b>没配口令时放行</b>（本地开发默认，启动时运行 {@link WebExposureConfig} 打 WARN）——
 * 一旦设置了 {@code xfg.web.api-key}（环境变量 {@code XFG_API_KEY}），所有请求都必须带口令。
 * <p>
 * <b>比较方式</b>：{@link MessageDigest#isEqual} 常量时间比较，避免按字符提前返回造成的时间侧信道。
 * <b>预检请求</b>（OPTIONS）直接放行：浏览器预检不带自定义请求头，拦它只会让 CORS 失效。
 *
 * @author miaowazhongzi
 */
@Slf4j
public class ApiKeyInterceptor implements HandlerInterceptor {

    /** 口令请求头 */
    public static final String HEADER = "X-API-Key";

    private final String expectedKey;

    public ApiKeyInterceptor(String expectedKey) {
        this.expectedKey = expectedKey == null ? "" : expectedKey.trim();
    }

    /** 配置了口令才启用（空口令 = 本地开发放行） */
    public boolean isEnabled() {
        return !expectedKey.isEmpty();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!isEnabled()) {
            return true;
        }
        // 预检请求不带自定义头，放行交给 CORS 处理器
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String presented = request.getHeader(HEADER);
        if (presented == null || presented.isBlank()) {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                presented = authorization.substring(7).trim();
            }
        }

        if (presented != null && matches(presented)) {
            return true;
        }

        log.warn("拒绝未授权请求：{} {}（缺少或错误的 {} 头，来源={}）",
                request.getMethod(), request.getRequestURI(), HEADER, request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"unauthorized\","
                + "\"hint\":\"需要 X-API-Key 请求头（值来自 xfg.web.api-key / XFG_API_KEY）\"}");
        return false;
    }

    /** 常量时间比较 */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expectedKey.getBytes(StandardCharsets.UTF_8));
    }

}
