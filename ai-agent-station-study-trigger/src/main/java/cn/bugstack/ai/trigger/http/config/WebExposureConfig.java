package cn.bugstack.ai.trigger.http.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * 接口暴露面收口（GAP §3 P2「健康端点暴露面」）
 * <p>
 * 原来每个 Controller 上都挂 {@code @CrossOrigin(origins = "*")}，等于**任何网页**都能在用户浏览器里
 * 调这些接口；健康端点还没有任何鉴权。这里把两件事一次收掉：
 * <ol>
 *   <li><b>CORS 收敛</b>：允许来源改成配置项 {@code xfg.web.cors-allowed-origins}
 *       （默认只允许本地前端 {@code http://localhost:5173} / {@code http://127.0.0.1:5173}），
 *       不再支持"随手写个 *"；</li>
 *   <li><b>共享口令鉴权</b>：配置 {@code xfg.web.api-key}（环境变量 {@code XFG_API_KEY}）后，
 *       {@code /api/**} 全部要求 {@code X-API-Key}，见 {@link ApiKeyInterceptor}。</li>
 * </ol>
 * <b>启动即自证</b>：没配口令会打一条 WARN（本地开发可接受），配了会打 INFO 说明已启用。
 *
 * @author miaowazhongzi
 */
@Slf4j
@Configuration
public class WebExposureConfig implements WebMvcConfigurer {

    private static final String DEFAULT_ORIGINS = "http://localhost:5173,http://127.0.0.1:5173";

    @Value("${xfg.web.api-key:}")
    private String apiKey;

    @Value("${xfg.web.cors-allowed-origins:" + DEFAULT_ORIGINS + "}")
    private String corsAllowedOrigins;

    @PostConstruct
    public void reportExposure() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("【暴露面】xfg.web.api-key 未配置：/api/** 无鉴权（本地开发可接受；生产必须设置 XFG_API_KEY）");
        } else {
            log.info("【暴露面】/api/** 已启用共享口令鉴权（{}），CORS 允许来源={}",
                    ApiKeyInterceptor.HEADER, origins());
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] allowed = origins().toArray(new String[0]);
        registry.addMapping("/api/**")
                .allowedOrigins(allowed)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", ApiKeyInterceptor.HEADER, "Authorization", "Accept")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiKeyInterceptor(apiKey)).addPathPatterns("/api/**");
    }

    private List<String> origins() {
        return Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

}
