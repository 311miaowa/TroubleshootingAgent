package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.model.valobj.datasource.SourceHealth;
import cn.bugstack.ai.domain.agent.service.datasource.DataSourceHealthService;
import cn.bugstack.ai.domain.agent.service.datasource.DataSourceHealthService.Snapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源健康端点（M4）
 * <p>
 * {@code GET /api/v1/datasource/health} —— 把"数据源是否真的可用"变成可查询的一等事实：
 * 前端 Data Sources 面板据此显示 Connected / Disconnected 与原因，而<b>不再</b>从查询结果反推。
 * <p>
 * <b>与 {@code ArmoryTestController} 分开</b>（M4 文档 §2 决策 3）：后者是 JMeter 性能测试端点，
 * 把健康语义混进去会让"读配置的压测接口"和"看数据源死活的运维接口"互相误解。
 * <p>
 * <b>只读</b>：不带任何请求体、不改任何配置、不触发重连。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/datasource")
public class DataSourceHealthController {

    @Resource
    private DataSourceHealthService dataSourceHealthService;

    /**
     * 查询全部已装配数据源的健康状态
     *
     * @param refresh 为 true 时忽略缓存、强制重新检查（决策 1 A：默认走 30s 缓存）
     * @return {@code {checkedAt, cached, summary:{total,healthy,unhealthy}, sources:[...]}}
     */
    @GetMapping("/health")
    public Map<String, Object> health(@RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        long start = System.currentTimeMillis();
        Snapshot snapshot = dataSourceHealthService.health(refresh);
        List<SourceHealth> sources = snapshot.getSources();

        long healthy = sources.stream().filter(SourceHealth::isHealthy).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", sources.size());
        summary.put("healthy", healthy);
        summary.put("unhealthy", sources.size() - healthy);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checkedAt", snapshot.getCheckedAt());
        body.put("cached", snapshot.isCached());
        body.put("summary", summary);
        body.put("sources", sources);

        log.info("数据源健康查询 refresh={} cached={} total={} unhealthy={} costMs={}",
                refresh, snapshot.isCached(), sources.size(), sources.size() - healthy,
                System.currentTimeMillis() - start);
        return body;
    }

}
