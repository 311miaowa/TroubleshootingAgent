package cn.bugstack.ai.config;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.ai.infrastructure.dao.IAiAgentFlowConfigDao;
import cn.bugstack.ai.infrastructure.dao.IAiClientConfigDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientConfig;
import cn.bugstack.ai.infrastructure.gateway.datasource.DataSourceProviderRegistrar;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 客户端配置定时刷新器
 * 实现配置热更新，无需重启应用
 * <p>
 * 变更检测覆盖两类配置：
 * <ol>
 *     <li>客户端组件配置（api/model/tool_mcp/advisor/config/system_prompt/rag_order/client，
 *         以及 M1 的 4 张数据源配置表），见 {@link IAiClientConfigDao#queryConfigVersion()}
 *         与 {@link IAiClientConfigDao#queryConfigRowTotal()}</li>
 *     <li>各阶段提示词（ai_agent_flow_config.step_prompt），
 *         见 {@link IAiAgentFlowConfigDao#queryMaxUpdateTime()}</li>
 * </ol>
 * 两个查询相互独立、各自捕获异常，任一失败不影响另一个。
 * <p>
 * <b>P0-T2 变更检测语义（重要）</b>：不再把"版本号"当成单调递增的数字，而是用
 * <b>配置指纹</b> {@code 版本:行数:提示词版本}，并<b>用等值比较</b>判断变更：
 * <ul>
 *     <li><b>为什么加行数</b>：只看 {@code MAX(update_time)} 检测不到删除——删掉最新那行会让 MAX 变小
 *         （甚至被别的表盖过而完全不变），删掉任意非最新行则 MAX 不动；加上行数后"增/删"必然改变指纹。
 *         "改"由 {@code update_time} 负责（配置表 DDL 均为 {@code ON UPDATE CURRENT_TIMESTAMP}，
 *         连软删 {@code status=0} 也会自动刷新）。</li>
 *     <li><b>为什么用 {@code !=} 而不是 {@code >}</b>：原来用 {@code >} 是为了避免时钟回拨/只读副本延迟
 *         造成的误触发，代价是"版本变小 = 有人删了配置"被静默忽略（P0-T2 的原始 bug）。
 *         热更新是幂等的（重建 Bean 而已），误触发的代价远小于漏更新，故改为等值比较。</li>
 * </ul>
 * <p>
 * <b>运维约定</b>：配置不要 {@code DELETE}（行数变化虽能被指纹捕捉，但会让版本号回退、审计线断裂）；
 * 停用配置请软删 {@code UPDATE ... SET status = 0}，{@code update_time} 会自动刷新。
 *
 * @author miaowazhongzi
 * 2025/8/5
 */
@Slf4j
@Component
public class
AiClientConfigRefreshScheduler {

    @Resource
    private DefaultArmoryStrategyFactory armoryStrategyFactory;

    @Resource
    private IAiClientConfigDao clientConfigDao;

    @Resource
    private IAiAgentFlowConfigDao agentFlowConfigDao;

    @Resource
    private DataSourceProviderRegistrar dataSourceProviderRegistrar;

    /**
     * 记录最后已生效的配置指纹（{@code 版本:行数:提示词版本}）
     * volatile 保证多线程环境下的可见性
     */
    private volatile String lastConfigFingerprint = "";

    /**
     * 定时检查配置变更并刷新
     * fixedDelay = 60000 表示每次执行完成后间隔 60 秒再次执行
     * synchronized 保证同一时间只有一个线程执行刷新逻辑
     */
    @Scheduled(fixedDelay = 60000)
    public synchronized void refreshConfig() {
        try {
            // 1. 汇总数据库中各配置表的当前指纹
            String currentFingerprint = queryCurrentConfigFingerprint();

            // 2. 等值比较：任何变化（新增/修改/删除）都要重载；
            //    首次执行时 lastConfigFingerprint 为空串，必然触发一次加载。
            if (!currentFingerprint.equals(lastConfigFingerprint)) {
                log.info("检测到配置变更 [上次：{}，当前：{}]，开始重新加载...", lastConfigFingerprint, currentFingerprint);

                // 3. 重新执行军备库构建流程
                reloadAllClientConfigs();

                // 4. 更新最后生效的指纹
                lastConfigFingerprint = currentFingerprint;
                log.info("配置重新加载完成，已生效指纹：{}", currentFingerprint);
            }
        } catch (Exception e) {
            log.error("定时刷新配置失败", e);
        }
    }

    /**
     * 汇总当前配置指纹：{@code 版本:行数:提示词版本}
     * <p>
     * 三个查询分别独立捕获异常：即使 {@code ai_agent_flow_config} 尚未补充 update_time 列
     * （需执行 docs/dev-ops/mysql/sql/upgrade-m0-1-flow-config-update-time.sql），
     * 其余配置的热更新依然可用，且应用不会因缺列而中断。
     *
     * @return 当前配置指纹；查询全部失败时返回 {@code "0:0:0"}
     */
    private String queryCurrentConfigFingerprint() {
        Long version = null;
        Long rows = null;
        Long flowVersion = null;

        try {
            version = clientConfigDao.queryConfigVersion();
        } catch (Exception e) {
            log.warn("查询客户端组件配置版本失败：{}", e.getMessage());
        }

        try {
            rows = clientConfigDao.queryConfigRowTotal();
        } catch (Exception e) {
            log.warn("查询客户端组件配置行数失败：{}", e.getMessage());
        }

        try {
            flowVersion = agentFlowConfigDao.queryMaxUpdateTime();
        } catch (Exception e) {
            log.warn("查询 ai_agent_flow_config 版本失败，该表可能尚未补充 update_time 列，" +
                    "请执行 docs/dev-ops/mysql/sql/upgrade-m0-1-flow-config-update-time.sql。原因：{}", e.getMessage());
        }

        return fingerprint(version, rows, flowVersion);
    }

    /**
     * 配置指纹：{@code 版本:行数:提示词版本}
     * <p>
     * 抽成静态方法是为了让单测能钉住"删除也必须被发现"这一设计
     * （见 {@code cn.bugstack.ai.test.p0.ConfigFingerprintTest}）。
     */
    public static String fingerprint(Long version, Long rows, Long flowVersion) {
        return (version == null ? 0L : version)
                + ":" + (rows == null ? 0L : rows)
                + ":" + (flowVersion == null ? 0L : flowVersion);
    }

    /**
     * 重新加载所有客户端配置
     */
    private void reloadAllClientConfigs() throws Exception {
        // 1. 查询所有启用的客户端 ID
        List<AiClientConfig> enabledConfigs = clientConfigDao.queryEnabledConfigs();
        List<String> clientIds = enabledConfigs.stream()
                .map(AiClientConfig::getSourceId)
                .distinct()
                .collect(Collectors.toList());

        if (clientIds.isEmpty()) {
            log.warn("未找到启用的客户端配置");
            return;
        }

        log.info("开始重新加载 {} 个客户端配置，客户端 ID 列表：{}", clientIds.size(), clientIds);

        // 0. 先刷新数据源 Provider 注册表：数据源配置（4 张新表）变更时，
        //    需在 armory 重建前让语义工具工厂看到最新的 Provider，否则新工具不生效。
        dataSourceProviderRegistrar.refresh();

        // 1. 执行军备库构建流程
        StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> handler
                = armoryStrategyFactory.armoryStrategyHandler();

        String result = handler.apply(
                ArmoryCommandEntity.builder()
                        .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                        .commandIdList(clientIds)
                        .build(),
                new DefaultArmoryStrategyFactory.DynamicContext()
        );

        log.info("客户端配置重新加载完成，结果：{}", result);
    }

}
