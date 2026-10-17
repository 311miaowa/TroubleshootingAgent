package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiDataSourceField;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据源字段字典表 DAO
 *
 * @author miaowazhongzi
 * @description 数据源字段字典表数据访问对象
 */
@Mapper
public interface IAiDataSourceFieldDao {

    /**
     * 插入字段字典
     */
    int insert(AiDataSourceField aiDataSourceField);

    /**
     * 根据ID更新
     */
    int updateById(AiDataSourceField aiDataSourceField);

    /**
     * 根据数据源ID删除
     */
    int deleteByDsId(String dsId);

    /**
     * 查询指定数据源下全部字段（不带 status 过滤，供配置核对使用）
     */
    List<AiDataSourceField> queryByDsId(@Param("dsId") String dsId);

    /**
     * 查询指定数据源、指定实体下启用的字段（按 id 升序，保证列顺序稳定）
     */
    List<AiDataSourceField> queryEnabledByDsIdAndEntityCode(@Param("dsId") String dsId, @Param("entityCode") String entityCode);

    /**
     * 查询指定数据源下全部启用字段（供按 entityCode 分组装配）
     */
    List<AiDataSourceField> queryEnabledByDsId(@Param("dsId") String dsId);

}
