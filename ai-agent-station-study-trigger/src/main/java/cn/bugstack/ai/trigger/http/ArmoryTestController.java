package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 军备库配置查询测试 Controller
 * 用于 JMeter 性能测试，提供串行和并发两种查询方式
 *
 * @author miaowazhongzi
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/test")
public class ArmoryTestController {

    @Resource
    private IAgentRepository repository;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    /**
     * 串行查询配置
     * 依次查询 6 张表，总耗时 = 6 次查询时间之和
     *
     * @param clientIds 客户端 ID 列表，逗号分隔
     * @return 查询结果和耗时
     */
    @GetMapping("/armory/serial")
    public Map<String, Object> queryArmorySerial(@RequestParam List<String> clientIds) {
        log.info("【串行查询】开始执行，clientIds: {}", clientIds);
        long startTime = System.currentTimeMillis();

        // 串行查询 6 张表
        List<AiClientApiVO> apiList = repository.queryAiClientApiVOListByClientIds(clientIds);
        log.info("查询完成：ai_client_api, 数量：{}", apiList.size());

        List<AiClientModelVO> modelList = repository.AiClientModelVOByClientIds(clientIds);
        log.info("查询完成：ai_client_model, 数量：{}", modelList.size());

        List<AiClientToolMcpVO> toolMcpList = repository.AiClientToolMcpVOByClientIds(clientIds);
        log.info("查询完成：ai_client_tool_mcp, 数量：{}", toolMcpList.size());

        Map<String, AiClientSystemPromptVO> promptMap = repository.queryAiClientSystemPromptMapByClientIds(clientIds);
        log.info("查询完成：ai_client_system_prompt, 数量：{}", promptMap.size());

        List<AiClientAdvisorVO> advisorList = repository.AiClientAdvisorVOByClientIds(clientIds);
        log.info("查询完成：ai_client_advisor, 数量：{}", advisorList.size());

        List<AiClientVO> clientList = repository.AiClientVOByClientIds(clientIds);
        log.info("查询完成：ai_client, 数量：{}", clientList.size());

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        log.info("【串行查询】执行完成，总耗时：{}ms", duration);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("queryType", "serial");
        result.put("duration", duration);
        result.put("clientIds", clientIds);
        result.put("data", buildDataMap(apiList, modelList, toolMcpList, promptMap, advisorList, clientList));

        return result;
    }

    /**
     * 并发查询配置
     * 使用线程池同时查询 6 张表，总耗时 ≈ 最慢的那次查询时间
     *
     * @param clientIds 客户端 ID 列表，逗号分隔
     * @return 查询结果和耗时
     */
    @GetMapping("/armory/parallel")
    public Map<String, Object> queryArmoryParallel(@RequestParam List<String> clientIds) {
        log.info("【并发查询】开始执行，clientIds: {}", clientIds);
        long startTime = System.currentTimeMillis();

        // 并发查询 6 张表
        CompletableFuture<List<AiClientApiVO>> apiFuture = CompletableFuture.supplyAsync(() -> {
            List<AiClientApiVO> result = repository.queryAiClientApiVOListByClientIds(clientIds);
            log.info("并发查询完成：ai_client_api, 数量：{}", result.size());
            return result;
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientModelVO>> modelFuture = CompletableFuture.supplyAsync(() -> {
            List<AiClientModelVO> result = repository.AiClientModelVOByClientIds(clientIds);
            log.info("并发查询完成：ai_client_model, 数量：{}", result.size());
            return result;
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientToolMcpVO>> toolMcpFuture = CompletableFuture.supplyAsync(() -> {
            List<AiClientToolMcpVO> result = repository.AiClientToolMcpVOByClientIds(clientIds);
            log.info("并发查询完成：ai_client_tool_mcp, 数量：{}", result.size());
            return result;
        }, threadPoolExecutor);

        CompletableFuture<Map<String, AiClientSystemPromptVO>> promptFuture = CompletableFuture.supplyAsync(() -> {
            Map<String, AiClientSystemPromptVO> result = repository.queryAiClientSystemPromptMapByClientIds(clientIds);
            log.info("并发查询完成：ai_client_system_prompt, 数量：{}", result.size());
            return result;
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientAdvisorVO>> advisorFuture = CompletableFuture.supplyAsync(() -> {
            List<AiClientAdvisorVO> result = repository.AiClientAdvisorVOByClientIds(clientIds);
            log.info("并发查询完成：ai_client_advisor, 数量：{}", result.size());
            return result;
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientVO>> clientFuture = CompletableFuture.supplyAsync(() -> {
            List<AiClientVO> result = repository.AiClientVOByClientIds(clientIds);
            log.info("并发查询完成：ai_client, 数量：{}", result.size());
            return result;
        }, threadPoolExecutor);

        // 等待所有查询完成
        CompletableFuture.allOf(apiFuture, modelFuture, toolMcpFuture, 
                               promptFuture, advisorFuture, clientFuture).join();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        log.info("【并发查询】执行完成，总耗时：{}ms", duration);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("queryType", "parallel");
        result.put("duration", duration);
        result.put("clientIds", clientIds);
        result.put("data", buildDataMap(
                apiFuture.join(), 
                modelFuture.join(), 
                toolMcpFuture.join(), 
                promptFuture.join(), 
                advisorFuture.join(), 
                clientFuture.join()
        ));

        return result;
    }

    /**
     * 批量查询接口（支持选择查询方式）
     *
     * @param clientIds 客户端 ID 列表，逗号分隔
     * @param type 查询类型：serial 或 parallel
     * @return 查询结果和耗时
     */
    @GetMapping("/armory/batch")
    public Map<String, Object> queryArmoryBatch(
            @RequestParam List<String> clientIds,
            @RequestParam(defaultValue = "parallel") String type) {
        
        log.info("【批量查询】开始执行，type: {}, clientIds: {}", type, clientIds);
        
        if ("serial".equalsIgnoreCase(type)) {
            return queryArmorySerial(clientIds);
        } else {
            return queryArmoryParallel(clientIds);
        }
    }

    /**
     * 构建数据 Map
     */
    private Map<String, Object> buildDataMap(
            List<AiClientApiVO> apiList,
            List<AiClientModelVO> modelList,
            List<AiClientToolMcpVO> toolMcpList,
            Map<String, AiClientSystemPromptVO> promptMap,
            List<AiClientAdvisorVO> advisorList,
            List<AiClientVO> clientList) {
        
        Map<String, Object> data = new HashMap<>();
        data.put("apiList", apiList);
        data.put("modelList", modelList);
        data.put("toolMcpList", toolMcpList);
        data.put("promptMap", promptMap);
        data.put("advisorList", advisorList);
        data.put("clientList", clientList);
        
        // 统计信息
        Map<String, Integer> statistics = new HashMap<>();
        statistics.put("apiCount", apiList.size());
        statistics.put("modelCount", modelList.size());
        statistics.put("toolMcpCount", toolMcpList.size());
        statistics.put("promptCount", promptMap.size());
        statistics.put("advisorCount", advisorList.size());
        statistics.put("clientCount", clientList.size());
        statistics.put("totalCount", apiList.size() + modelList.size() + toolMcpList.size() + 
                                      promptMap.size() + advisorList.size() + clientList.size());
        
        data.put("statistics", statistics);
        
        return data;
    }
}
