package cn.bugstack.ai.infrastructure.gateway.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProviderFactory;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import org.springframework.stereotype.Component;

/**
 * MySQL 数据源 Provider 工厂（只读 JDBC；P2 收敛：替代注册器里的 {@code switch} 分支）
 *
 * @author miaowazhongzi
 */
@Component
public class MysqlOrderProviderFactory implements IDataSourceProviderFactory {

    @Override
    public String dsType() {
        return MysqlOrderProvider.DS_TYPE;
    }

    @Override
    public IDataSourceProvider create(IDataSourceRepository.DataSourceConfig config,
                                      IDataSourceRepository repository) {
        return new MysqlOrderProvider(config.getDsId(), config, repository);
    }

}
