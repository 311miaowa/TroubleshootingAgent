package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiDataSourceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据源实体配置表 DAO
 *
 * @author miaowazhongzi
 * @description 数据源实体配置表数据访问对象
 */
@Mapper
public interface IAiDataSourceEntityDao {

    /**
     * 插入实体配置
     */
    int insert(AiDataSourceEntity aiDataSourceEntity);

    /**
     * 根据ID更新
     */
    int updateById(AiDataSourceEntity aiDataSourceEntity);

    /**
     * 根据数据源ID与实体编码更新
     */
    int updateByDsIdAndEntityCode(AiDataSourceEntity aiDataSourceEntity);

    /**
     * 根据数据源ID删除
     */
    int deleteByDsId(String dsId);

    /**
     * 按数据源ID与实体编码查询（不带 status 过滤，供配置核对使用）
     */
    AiDataSourceEntity queryByDsIdAndEntityCode(@Param("dsId") String dsId, @Param("entityCode") String entityCode);

    /**
     * 查询指定数据源下启用的实体
     */
    List<AiDataSourceEntity> queryEnabledByDsId(@Param("dsId") String dsId);

    /**
     * 查询指定数据源下、指定能力的启用实体
     */
    List<AiDataSourceEntity> queryEnabledByDsIdAndCapability(@Param("dsId") String dsId, @Param("capability") String capability);

    /**
     * 查询全部启用实体
     */
    List<AiDataSourceEntity> queryEnabled();

}
