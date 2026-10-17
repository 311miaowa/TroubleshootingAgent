package cn.bugstack.ai.test.performance;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 数据加载性能对比测试
 * 对比串行查询 vs 并发查询的性能差异
 *
 * @author miaowazhongzi
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class LoadDataPerformanceTest {

    @Resource
    private IAgentRepository repository;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    // 测试用的客户端ID列表
    private static final List<String> CLIENT_ID_LIST = Arrays.asList("3001", "3002", "3003");

    // 预热次数
    private static final int WARMUP_ITERATIONS = 5;

    // 正式测试次数（P99 需要更多样本）
    private static final int TEST_ITERATIONS = 100;

    /**
     * 计算 P99 延迟
     * @param sortedLatencies 已排序的延迟列表（升序）
     * @return P99 值
     */
    private long calculateP99(List<Long> sortedLatencies) {
        if (sortedLatencies.isEmpty()) {
            return 0;
        }
        int p99Index = (int) Math.ceil(sortedLatencies.size() * 0.99) - 1;
        return sortedLatencies.get(Math.max(0, p99Index));
    }

    /**
     * 计算 P95 延迟
     * @param sortedLatencies 已排序的延迟列表（升序）
     * @return P95 值
     */
    private long calculateP95(List<Long> sortedLatencies) {
        if (sortedLatencies.isEmpty()) {
            return 0;
        }
        int p95Index = (int) Math.ceil(sortedLatencies.size() * 0.95) - 1;
        return sortedLatencies.get(Math.max(0, p95Index));
    }

    /**
     * 计算平均延迟
     * @param latencies 延迟列表
     * @return 平均值
     */
    private double calculateAverage(List<Long> latencies) {
        if (latencies.isEmpty()) {
            return 0;
        }
        return latencies.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    /**
     * 串行查询：依次查询6张表
     */
    private long testSerialQuery() {
        long startTime = System.currentTimeMillis();

        // 串行查询6张表
        List<AiClientApiVO> apiList = repository.queryAiClientApiVOListByClientIds(CLIENT_ID_LIST);
        List<AiClientModelVO> modelList = repository.AiClientModelVOByClientIds(CLIENT_ID_LIST);
        List<AiClientToolMcpVO> toolMcpList = repository.AiClientToolMcpVOByClientIds(CLIENT_ID_LIST);
        Map<String, AiClientSystemPromptVO> promptMap = repository.queryAiClientSystemPromptMapByClientIds(CLIENT_ID_LIST);
        List<AiClientAdvisorVO> advisorList = repository.AiClientAdvisorVOByClientIds(CLIENT_ID_LIST);
        List<AiClientVO> clientList = repository.AiClientVOByClientIds(CLIENT_ID_LIST);

        long endTime = System.currentTimeMillis();
        return endTime - startTime;
    }

    /**
     * 并发查询：使用线程池同时查询6张表
     */
    private long testParallelQuery() {
        long startTime = System.currentTimeMillis();

        // 并发查询6张表
        CompletableFuture<List<AiClientApiVO>> apiFuture = CompletableFuture.supplyAsync(() ->
                repository.queryAiClientApiVOListByClientIds(CLIENT_ID_LIST), threadPoolExecutor);

        CompletableFuture<List<AiClientModelVO>> modelFuture = CompletableFuture.supplyAsync(() ->
                repository.AiClientModelVOByClientIds(CLIENT_ID_LIST), threadPoolExecutor);

        CompletableFuture<List<AiClientToolMcpVO>> toolMcpFuture = CompletableFuture.supplyAsync(() ->
                repository.AiClientToolMcpVOByClientIds(CLIENT_ID_LIST), threadPoolExecutor);

        CompletableFuture<Map<String, AiClientSystemPromptVO>> promptFuture = CompletableFuture.supplyAsync(() ->
                repository.queryAiClientSystemPromptMapByClientIds(CLIENT_ID_LIST), threadPoolExecutor);

        CompletableFuture<List<AiClientAdvisorVO>> advisorFuture = CompletableFuture.supplyAsync(() ->
                repository.AiClientAdvisorVOByClientIds(CLIENT_ID_LIST), threadPoolExecutor);

        CompletableFuture<List<AiClientVO>> clientFuture = CompletableFuture.supplyAsync(() ->
                repository.AiClientVOByClientIds(CLIENT_ID_LIST), threadPoolExecutor);

        // 等待所有查询完成
        CompletableFuture.allOf(apiFuture, modelFuture, toolMcpFuture, promptFuture, advisorFuture, clientFuture).join();

        long endTime = System.currentTimeMillis();
        return endTime - startTime;
    }

    @Test
    public void testPerformanceComparison() {
        log.info("========================================");
        log.info("开始性能对比测试");
        log.info("测试客户端ID: {}", CLIENT_ID_LIST);
        log.info("预热次数: {}, 正式测试次数: {}", WARMUP_ITERATIONS, TEST_ITERATIONS);
        log.info("========================================");

        // 预热 - 串行
        log.info("【预热阶段】串行查询...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            testSerialQuery();
        }

        // 预热 - 并发
        log.info("【预热阶段】并发查询...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            testParallelQuery();
        }

        // 正式测试 - 串行
        log.info("【正式测试】串行查询 {} 次...", TEST_ITERATIONS);
        List<Long> serialLatencies = new ArrayList<>(TEST_ITERATIONS);

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            long time = testSerialQuery();
            serialLatencies.add(time);
            if ((i + 1) % 10 == 0) {
                log.info("串行查询第{}次耗时：{}ms", i + 1, time);
            }
        }

        // 正式测试 - 并发
        log.info("【正式测试】并发查询 {} 次...", TEST_ITERATIONS);
        List<Long> parallelLatencies = new ArrayList<>(TEST_ITERATIONS);

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            long time = testParallelQuery();
            parallelLatencies.add(time);
            if ((i + 1) % 10 == 0) {
                log.info("并发查询第{}次耗时：{}ms", i + 1, time);
            }
        }

        // 排序用于计算百分位数
        Collections.sort(serialLatencies);
        Collections.sort(parallelLatencies);

        // 计算统计结果
        long serialP99 = calculateP99(serialLatencies);
        long serialP95 = calculateP95(serialLatencies);
        double serialAvg = calculateAverage(serialLatencies);
        long serialMin = serialLatencies.get(0);
        long serialMax = serialLatencies.get(serialLatencies.size() - 1);

        long parallelP99 = calculateP99(parallelLatencies);
        long parallelP95 = calculateP95(parallelLatencies);
        double parallelAvg = calculateAverage(parallelLatencies);
        long parallelMin = parallelLatencies.get(0);
        long parallelMax = parallelLatencies.get(parallelLatencies.size() - 1);

        // P99 性能提升比例
        double p99ImprovementRatio = (double) serialP99 / parallelP99;
        double p99TimeReductionRatio = (double) (serialP99 - parallelP99) / serialP99 * 100;
        
        // 平均性能提升比例
        double avgImprovementRatio = serialAvg / parallelAvg;
        double avgTimeReductionRatio = (serialAvg - parallelAvg) / serialAvg * 100;

        // 输出结果
        log.info("========================================");
        log.info("【性能对比结果】");
        log.info("----------------------------------------");
        log.info("串行查询:");
        log.info("  - P99 耗时：{}ms", serialP99);
        log.info("  - P95 耗时：{}ms", serialP95);
        log.info("  - 平均耗时：{}ms", Math.round(serialAvg));
        log.info("  - 最小耗时：{}ms", serialMin);
        log.info("  - 最大耗时：{}ms", serialMax);
        log.info("----------------------------------------");
        log.info("并发查询:");
        log.info("  - P99 耗时：{}ms", parallelP99);
        log.info("  - P95 耗时：{}ms", parallelP95);
        log.info("  - 平均耗时：{}ms", Math.round(parallelAvg));
        log.info("  - 最小耗时：{}ms", parallelMin);
        log.info("  - 最大耗时：{}ms", parallelMax);
        log.info("----------------------------------------");
        log.info("P99 性能提升:");
        log.info("  - 速度提升：{} 倍", String.format("%.2f", p99ImprovementRatio));
        log.info("  - 耗时降低：{}%", String.format("%.1f", p99TimeReductionRatio));
        log.info("  - P99 节省：{}ms", serialP99 - parallelP99);
        log.info("----------------------------------------");
        log.info("平均性能提升:");
        log.info("  - 速度提升：{} 倍", String.format("%.2f", avgImprovementRatio));
        log.info("  - 耗时降低：{}%", String.format("%.1f", avgTimeReductionRatio));
        log.info("  - 平均节省：{}ms", Math.round(serialAvg - parallelAvg));
        log.info("========================================");

        // 简历用的一句话总结
        log.info("\n【简历描述】");
        log.info("采用线程池并发加载多表数据，P99 延迟从 {}ms 降至 {}ms，性能提升 {} 倍，耗时降低 {}%",
                serialP99, parallelP99, String.format("%.1f", p99ImprovementRatio), String.format("%.1f", p99TimeReductionRatio));
    }

    @Test
    public void testSingleComparison() {
        // 简单对比，只跑一次
        log.info("单次对比测试");

        long serialTime = testSerialQuery();
        log.info("串行查询耗时: {}ms", serialTime);

        long parallelTime = testParallelQuery();
        log.info("并发查询耗时: {}ms", parallelTime);

        double ratio = (double) serialTime / parallelTime;
        log.info("性能提升: {:.2f} 倍", ratio);
    }
}
