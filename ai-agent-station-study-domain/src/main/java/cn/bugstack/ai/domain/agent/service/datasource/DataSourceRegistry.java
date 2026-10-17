package cn.bugstack.ai.domain.agent.service.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源注册表（内存实现）
 * <p>
 * <b>本步（M1.0）刻意保持惰性</b>：不在启动期去读 {@code ai_data_source} 表自动装配 Provider。
 * 原因有两条：
 * <ol>
 *     <li>M1.0 的验收标准是"零行为变更"。若在启动路径上新增一次数据库查询与失败面，
 *         那么升级脚本还没执行的环境就可能启动异常——这不是零行为变更。</li>
 *     <li>M1.0 还没有任何 Provider 实现类，启动期装配出来的注册表必然为空，做了也无意义。</li>
 * </ol>
 * 真正的"配置 → Provider → 注册"时序放到 M1.1（Provider 实现出来的那一步），
 * 那时才有东西可注册，也才需要处理"ES MCP 尚未就绪"这类容错。
 * <p>
 * 因此本类在 M1.0 里是暂未被调用方引用的组件，其正确性由单测直接驱动验证。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
@Component
public class DataSourceRegistry implements IDataSourceRegistry {

    /**
     * dsId → Provider
     */
    private final Map<String, IDataSourceProvider> providers = new ConcurrentHashMap<>();

    @Override
    public void register(IDataSourceProvider provider) {
        if (provider == null || provider.dsId() == null || provider.dsId().isBlank()) {
            throw new IllegalArgumentException("注册数据源失败：provider 或 dsId 为空");
        }
        IDataSourceProvider previous = providers.put(provider.dsId(), provider);
        if (previous != null) {
            log.info("数据源已覆盖注册 dsId={} dsType={}", provider.dsId(), provider.dsType());
            closeQuietly(previous);
        } else {
            log.info("数据源注册成功 dsId={} dsType={}", provider.dsId(), provider.dsType());
        }
    }

    @Override
    public void unregister(String dsId) {
        IDataSourceProvider removed = providers.remove(dsId);
        if (removed != null) {
            closeQuietly(removed);
            log.info("数据源已注销 dsId={}", dsId);
        }
    }

    @Override
    public Optional<IDataSourceProvider> get(String dsId) {
        if (dsId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(dsId));
    }

    @Override
    public List<IDataSourceProvider> listByTenant(String tenantId) {
        // M1.0 的 Provider 尚未携带租户信息（多租户落库与隔离在 M5 落地），
        // 这里先返回全部快照，待 Provider 具备租户属性后再按 tenantId 过滤。
        // 注意：不要在此处假装过滤（如 filter(p -> true)），那会误导调用方以为隔离已生效。
        return new ArrayList<>(providers.values());
    }

    /**
     * 覆盖注册/注销时释放旧实例（Provider 持有连接池或 MCP 句柄）
     */
    private void closeQuietly(IDataSourceProvider provider) {
        try {
            if (provider instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception e) {
            log.warn("关闭被替换的数据源失败 dsId={}", provider.dsId(), e);
        }
    }

}
