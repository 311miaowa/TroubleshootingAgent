package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiDataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据源配置表 DAO
 * <p>
 * 查询方法一律带 status 过滤：数据源的停用必须立即对上层不可见，
 * 否则"改 status 即下线"这条运维手段会失真。
 *
 * @author miaowazhongzi
 * @description 数据源配置表数据访问对象
 */
@Mapper
public interface IAiDataSourceDao {

    /**
     * 插入数据源配置
     */
    int insert(AiDataSource aiDataSource);

    /**
     * 根据ID更新
     */
    int updateById(AiDataSource aiDataSource);

    /**
     * 根据数据源ID更新
     */
    int updateByDsId(AiDataSource aiDataSource);

    /**
     * 根据数据源ID删除
     */
    int deleteByDsId(String dsId);

    /**
     * 根据数据源ID查询（不带 status 过滤，供配置核对使用）
     */
    AiDataSource queryByDsId(String dsId);

    /**
     * 查询全部启用的数据源
     */
    List<AiDataSource> queryEnabled();

    /**
     * 按租户查询启用的数据源
     */
    List<AiDataSource> queryEnabledByTenant(@Param("tenantId") String tenantId);

}
