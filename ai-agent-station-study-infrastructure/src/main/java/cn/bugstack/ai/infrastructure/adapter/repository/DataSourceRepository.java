package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.infrastructure.dao.IAiDataSourceDao;
import cn.bugstack.ai.infrastructure.dao.IAiDataSourceEntityDao;
import cn.bugstack.ai.infrastructure.dao.IAiDataSourceFieldDao;
import cn.bugstack.ai.infrastructure.dao.IAiStageToolPolicyDao;
import cn.bugstack.ai.infrastructure.dao.po.AiDataSource;
import cn.bugstack.ai.infrastructure.dao.po.AiDataSourceEntity;
import cn.bugstack.ai.infrastructure.dao.po.AiDataSourceField;
import cn.bugstack.ai.infrastructure.dao.po.AiStageToolPolicy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一数据源配置仓储服务
 * <p>
 * 只做"读配置 + 装配成领域对象"，不做连接、不发查询。
 * 所有查询都走 DAO 里带 {@code status = 1} 的语句，本层不再二次过滤——
 * 过滤只在一个地方做，避免"某条路径忘了过滤"这种事后难查的问题。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
@Repository
public class DataSourceRepository implements IDataSourceRepository {

    @Resource
    private IAiDataSourceDao aiDataSourceDao;

    @Resource
    private IAiDataSourceEntityDao aiDataSourceEntityDao;

    @Resource
    private IAiDataSourceFieldDao aiDataSourceFieldDao;

    @Resource
    private IAiStageToolPolicyDao aiStageToolPolicyDao;

    @Override
    public Map<String, DataSourceConfig> queryEnabledDataSources() {
        return toConfigMap(aiDataSourceDao.queryEnabled());
    }

    @Override
    public Map<String, DataSourceConfig> queryEnabledDataSources(String tenantId) {
        return toConfigMap(aiDataSourceDao.queryEnabledByTenant(tenantId));
    }

    @Override
    public List<EntityMeta> queryEnabledEntities(String dsId) {
        List<AiDataSourceEntity> entities = aiDataSourceEntityDao.queryEnabledByDsId(dsId);
        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, List<EntityMeta.FieldMeta>> fieldsByEntity = queryEnabledFields(dsId);

        // 数据源级 time_field 是实体的默认时间字段；字段字典里 is_time_field=1 的字段优先。
        // 两者都没有时留空，由 Provider 决定是否报错——不在此处编造一个默认值。
        AiDataSource dataSource = aiDataSourceDao.queryByDsId(dsId);
        String dsTimeField = dataSource == null ? null : dataSource.getTimeField();

        List<EntityMeta> result = new ArrayList<>(entities.size());
        for (AiDataSourceEntity entity : entities) {
            List<EntityMeta.FieldMeta> fields = fieldsByEntity.getOrDefault(entity.getEntityCode(), new ArrayList<>());
            result.add(EntityMeta.builder()
                    .dsId(entity.getDsId())
                    .entityCode(entity.getEntityCode())
                    .entityName(entity.getEntityName())
                    .entityDesc(entity.getEntityDesc())
                    .capability(entity.getCapability())
                    .physicalName(entity.getPhysicalName())
                    .timeField(resolveTimeField(fields, dsTimeField))
                    .fields(fields)
                    .build());
        }
        return result;
    }

    @Override
    public Map<String, List<EntityMeta.FieldMeta>> queryEnabledFields(String dsId) {
        List<AiDataSourceField> fields = aiDataSourceFieldDao.queryEnabledByDsId(dsId);
        Map<String, List<EntityMeta.FieldMeta>> grouped = new LinkedHashMap<>();
        if (fields == null) {
            return grouped;
        }
        for (AiDataSourceField field : fields) {
            grouped.computeIfAbsent(field.getEntityCode(), k -> new ArrayList<>()).add(toFieldMeta(field));
        }
        return grouped;
    }

    @Override
    public List<EntityConfig> queryEnabledEntityConfigs(String dsId) {
        List<AiDataSourceEntity> entities = aiDataSourceEntityDao.queryEnabledByDsId(dsId);
        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, List<EntityMeta.FieldMeta>> fieldsByEntity = queryEnabledFields(dsId);
        AiDataSource dataSource = aiDataSourceDao.queryByDsId(dsId);
        String dsTimeField = dataSource == null ? null : dataSource.getTimeField();

        List<EntityConfig> result = new ArrayList<>(entities.size());
        for (AiDataSourceEntity entity : entities) {
            List<EntityMeta.FieldMeta> fields = fieldsByEntity.getOrDefault(entity.getEntityCode(), new ArrayList<>());
            result.add(EntityConfig.builder()
                    .dsId(entity.getDsId())
                    .entityCode(entity.getEntityCode())
                    .entityName(entity.getEntityName())
                    .entityDesc(entity.getEntityDesc())
                    .capability(entity.getCapability())
                    .physicalName(entity.getPhysicalName())
                    .queryTemplate(entity.getQueryTemplate())
                    .timeField(resolveTimeField(fields, dsTimeField))
                    .build());
        }
        return result;
    }

    @Override
    public Map<String, List<String>> queryStageToolPolicy() {
        List<AiStageToolPolicy> policies = aiStageToolPolicyDao.queryAll();
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        if (policies == null) {
            return grouped;
        }
        // 只收敛 allowed=1 的能力：调用方关心的是"白名单"。
        // allowed=0 的行是有意义的记录（明确写过"不允许"），但语义上等价于"不在白名单里"，
        // 因此不在此处回传；需要核对显式拒绝项时用 queryByStageCode。
        for (AiStageToolPolicy policy : policies) {
            if (policy.getAllowed() != null && policy.getAllowed() == 1) {
                grouped.computeIfAbsent(policy.getStageCode(), k -> new ArrayList<>()).add(policy.getCapability());
            }
        }
        return grouped;
    }

    @Override
    public List<String> queryAllowedCapabilities(String stageCode) {
        List<String> capabilities = aiStageToolPolicyDao.queryAllowedCapabilities(stageCode);
        return capabilities == null ? new ArrayList<>() : capabilities;
    }

    /**
     * 解析实体时间字段：字段字典中的 is_time_field=1 优先，其次数据源级 time_field，都没有则为 null
     */
    private String resolveTimeField(List<EntityMeta.FieldMeta> fields, String dsTimeField) {
        if (fields != null) {
            for (EntityMeta.FieldMeta field : fields) {
                if (field.isTimeField()) {
                    return field.getFieldName();
                }
            }
        }
        return dsTimeField;
    }

    private Map<String, DataSourceConfig> toConfigMap(List<AiDataSource> dataSources) {
        Map<String, DataSourceConfig> map = new HashMap<>();
        if (dataSources == null) {
            return map;
        }
        for (AiDataSource ds : dataSources) {
            map.put(ds.getDsId(), DataSourceConfig.builder()
                    .dsId(ds.getDsId())
                    .dsName(ds.getDsName())
                    .dsType(ds.getDsType())
                    .tenantId(ds.getTenantId())
                    .mcpId(ds.getMcpId())
                    .connConfig(ds.getConnConfig())
                    .timeField(ds.getTimeField())
                    .maxRows(ds.getMaxRows() == null ? 0 : ds.getMaxRows())
                    .timeoutMs(ds.getTimeoutMs() == null ? 0 : ds.getTimeoutMs())
                    .status(ds.getStatus())
                    .build());
        }
        return map;
    }

    private EntityMeta.FieldMeta toFieldMeta(AiDataSourceField field) {
        return EntityMeta.FieldMeta.builder()
                .fieldName(field.getFieldName())
                .fieldLabel(field.getFieldLabel())
                .fieldType(field.getFieldType())
                .timeField(isTrue(field.getIsTimeField()))
                .filterable(isTrue(field.getIsFilterable()))
                .sampleValue(field.getSampleValue())
                .sensitive(isTrue(field.getSensitive()))
                .build();
    }

    private boolean isTrue(Integer flag) {
        return flag != null && flag == 1;
    }

}
