package cn.bugstack.ai.infrastructure.gateway.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProviderFactory;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 数据源 Provider 工厂（P2 收敛：替代注册器里的 {@code switch} 分支）
 *
 * @author miaowazhongzi
 */
@Component
public class ElasticsearchProviderFactory implements IDataSourceProviderFactory {

    @Override
    public String dsType() {
        return ElasticsearchProvider.DS_TYPE;
    }

    @Override
    public IDataSourceProvider create(IDataSourceRepository.DataSourceConfig config,
                                      IDataSourceRepository repository) {
        return new ElasticsearchProvider(config.getDsId(), config, repository);
    }

}
