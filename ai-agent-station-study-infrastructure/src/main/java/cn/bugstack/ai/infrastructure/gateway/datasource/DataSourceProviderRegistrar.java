package cn.bugstack.ai.infrastructure.gateway.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProviderFactory;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.service.datasource.DataSourceHealthService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据源 Provider 注册器
 * <p>
 * 启动时读取 {@code ai_data_source}（启用状态）配置，按 {@code ds_type} 查表找到对应
 * {@link IDataSourceProviderFactory} 并注册 Provider。
 * <p>
 * <b>P2 收敛</b>：此前是 {@code switch (config.getDsType())} 硬编码 elasticsearch/mysql/postgres，
 * 新增数据源必须改本类；现在每种类型由自己的工厂 {@code @Component} 声明，
 * 本类只维护 {@code ds_type → 工厂} 的查找表，**新增数据源无需改动这里**。
 * <p>
 * 依赖方向 infrastructure → domain：本类注入 domain 的 {@link IDataSourceRegistry}，符合 M1 文档 §3 的约定。
 * <p>
 * {@link #refresh()} 可重复执行（覆盖式注册，旧实例由 {@link IDataSourceRegistry#register} 负责关闭），
 * 供配置热更新链路在 armory 重建前调用。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
@Component
public class DataSourceProviderRegistrar {

    @Resource
    private IDataSourceRepository dataSourceRepository;

    @Resource
    private IDataSourceRegistry dataSourceRegistry;

    @Resource
    private DataSourceHealthService dataSourceHealthService;

    /**
     * 各数据源类型对应的工厂（由 Spring 注入所有 {@link IDataSourceProviderFactory} 实现）
     */
    @Resource
    private List<IDataSourceProviderFactory> providerFactories;

    /**
     * dsType → 工厂；{@link #init()} 时按注入的工厂列表构建
     */
    private Map<String, IDataSourceProviderFactory> factories = Map.of();

    @PostConstruct
    public void init() {
        this.factories = buildFactoryIndex(providerFactories);
        refresh();
    }

    /**
     * 把工厂列表编成查找表（同名 dsType 以后者为准并 WARN：说明有两个实现抢同一个类型）
     * <p>
     * 抽成 public static 便于单测直接覆盖"重名 / 缺失 / 未知类型"几种情况，不必起 Spring 容器；
     * 也便于将来在别处复用同一套索引规则。
     */
    public static Map<String, IDataSourceProviderFactory> buildFactoryIndex(List<IDataSourceProviderFactory> factories) {
        Map<String, IDataSourceProviderFactory> index = new LinkedHashMap<>();
        if (factories == null) {
            return Collections.emptyMap();
        }
        for (IDataSourceProviderFactory factory : factories) {
            if (factory == null || factory.dsType() == null || factory.dsType().isBlank()) {
                log.warn("忽略未声明 dsType 的数据源 Provider 工厂：{}", factory == null ? "null" : factory.getClass().getName());
                continue;
            }
            IDataSourceProviderFactory previous = index.put(factory.dsType(), factory);
            if (previous != null) {
                log.warn("dsType={} 存在多个 Provider 工厂，后者覆盖前者：{} ← {}", factory.dsType(),
                        factory.getClass().getSimpleName(), previous.getClass().getSimpleName());
            }
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * 重新装配并注册所有启用的数据源 Provider
     * <p>
     * M4 起，装配完成后<b>立即跑一次健康检查并打印汇总</b>：数据源不可用不再是"首次查询才暴露的
     * 泛化异常"，而是启动日志里一条含 dsId 与"要注入哪个环境变量"的 WARN。
     * 这里同时也是热更新路径，故每次重装都会重新体检（M4.1 会在此处一并失效健康缓存）。
     * <p>
     * <b>锁范围</b>：只有"装配 + 注册 + 清理"在锁内；健康检查会做真实网络探测（每源一次、默认 5s 超时），
     * 放在锁外执行，避免热更新线程长时间持锁把其它 refresh 调用堵住。
     */
    public void refresh() {
        try {
            synchronized (this) {
                // 防御：若索引还没建（理论上 init 已保证，但热更新线程不该依赖调用时序），这里补建一次
                if (factories.isEmpty() && providerFactories != null) {
                    this.factories = buildFactoryIndex(providerFactories);
                }

                // 配置即将重装：旧 Provider 的健康快照立即作废，避免热更新窗口期端点/UI 仍显示旧状态
                // （M4.1 纪律：热更新后不得继续用旧 Provider 的检查结果）
                dataSourceHealthService.invalidate();

                Map<String, IDataSourceRepository.DataSourceConfig> configs = dataSourceRepository.queryEnabledDataSources();
                if (configs == null || configs.isEmpty()) {
                    log.warn("未查询到启用的数据源配置，跳过 Provider 注册");
                    return;
                }

                Set<String> targetDsIds = new HashSet<>();
                for (IDataSourceRepository.DataSourceConfig config : configs.values()) {
                    IDataSourceProviderFactory factory = factories.get(config.getDsType());
                    if (factory == null) {
                        log.warn("没有匹配的 Provider 工厂，跳过 dsId={} dsType={}（已注册类型={}）",
                                config.getDsId(), config.getDsType(), factories.keySet());
                        continue;
                    }
                    IDataSourceProvider provider = factory.create(config, dataSourceRepository);
                    dataSourceRegistry.register(provider);
                    targetDsIds.add(config.getDsId());
                    log.info("数据源 Provider 注册成功 dsId={} dsType={} factory={}",
                            config.getDsId(), config.getDsType(), factory.getClass().getSimpleName());
                }

                // 清理不再启用/已删除的数据源：否则其 Provider（含 Hikari 连接池）会一直留在注册表里，
                // 与"覆盖式重装"的语义不符。@see IDataSourceRegistry#unregister
                for (IDataSourceProvider existing : dataSourceRegistry.listByTenant(null)) {
                    if (existing.dsId() != null && !targetDsIds.contains(existing.dsId())) {
                        dataSourceRegistry.unregister(existing.dsId());
                        log.info("已注销不再启用的数据源 dsId={}", existing.dsId());
                    }
                }
            }
        } finally {
            // 装配完即体检：不健康 → WARN（降级启动，不 fail-fast）；锁外执行，见方法注释
            dataSourceHealthService.logReport();
        }
    }

}
