package cn.bugstack.ai.test.p2;

import cn.bugstack.ai.trigger.http.config.ApiKeyInterceptor;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * P2 接口暴露面收口单测（纯单测：直接调拦截器，不起 Spring 容器、不发网络）
 * <p>
 * 钉住四件事：
 * ① 没配口令 = 放行（本地开发模式，配置类会打 WARN）；
 * ② 配了口令后，缺头 / 错口令一律 **401** 并且带可读提示；
 * ③ 正确口令两种传法（{@code X-API-Key}、{@code Authorization: Bearer}）都放行；
 * ④ 浏览器 CORS 预检（OPTIONS）必须放行，否则前端会被自己拦住。
 *
 * @author miaowazhongzi
 */
public class ApiExposureTest {

    private static final String KEY = "test-shared-key-123";

    @Test
    public void noKeyConfigured_allowsEverything() throws Exception {
        ApiKeyInterceptor interceptor = new ApiKeyInterceptor("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/datasource/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Assert.assertFalse("空口令不应启用鉴权：", interceptor.isEnabled());
        Assert.assertTrue("未配置口令时应放行：", interceptor.preHandle(request, response, new Object()));
        Assert.assertEquals("不应写 401：", 200, response.getStatus());
    }

    @Test
    public void keyConfigured_missingHeader_returns401() throws Exception {
        ApiKeyInterceptor interceptor = new ApiKeyInterceptor(KEY);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/datasource/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Assert.assertTrue("配了口令应启用鉴权：", interceptor.isEnabled());
        Assert.assertFalse("缺头必须拒绝：", interceptor.preHandle(request, response, new Object()));
        Assert.assertEquals("应返回 401：", 401, response.getStatus());
        Assert.assertTrue("响应体应说明原因：" + response.getContentAsString(),
                response.getContentAsString().contains("unauthorized"));
    }

    @Test
    public void keyConfigured_wrongKey_returns401() throws Exception {
        ApiKeyInterceptor interceptor = new ApiKeyInterceptor(KEY);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agent/auto_agent");
        request.addHeader(ApiKeyInterceptor.HEADER, "not-the-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Assert.assertFalse("错口令必须拒绝：", interceptor.preHandle(request, response, new Object()));
        Assert.assertEquals("应返回 401：", 401, response.getStatus());
    }

    @Test
    public void keyConfigured_correctHeader_allows() throws Exception {
        ApiKeyInterceptor interceptor = new ApiKeyInterceptor(KEY);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agent/auto_agent");
        request.addHeader(ApiKeyInterceptor.HEADER, KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        Assert.assertTrue("正确口令应放行：", interceptor.preHandle(request, response, new Object()));
        Assert.assertEquals("不应写 401：", 200, response.getStatus());
    }

    @Test
    public void keyConfigured_bearerToken_allows() throws Exception {
        ApiKeyInterceptor interceptor = new ApiKeyInterceptor(KEY);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/datasource/health");
        request.addHeader("Authorization", "Bearer " + KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        Assert.assertTrue("Bearer 形式也应放行：", interceptor.preHandle(request, response, new Object()));
    }

    @Test
    public void corsPreflight_isNeverBlocked() throws Exception {
        ApiKeyInterceptor interceptor = new ApiKeyInterceptor(KEY);
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/agent/auto_agent");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Assert.assertTrue("预检请求必须放行（否则 CORS 直接失效）：",
                interceptor.preHandle(request, response, new Object()));
    }

}
