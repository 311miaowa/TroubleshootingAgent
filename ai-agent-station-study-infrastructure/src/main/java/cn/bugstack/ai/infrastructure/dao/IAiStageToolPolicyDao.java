package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiStageToolPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 阶段-工具能力策略表 DAO
 * <p>
 * 该表决定"哪个阶段能看到哪些能力"，是解决"四步链被短路"的配置来源。
 * 注意 {@code allowed=0} 的行是<b>有意义的记录</b>（显式拒绝），不是可以省略的行。
 *
 * @author miaowazhongzi
 * @description 阶段-工具能力策略表数据访问对象
 */
@Mapper
public interface IAiStageToolPolicyDao {

    /**
     * 插入策略
     */
    int insert(AiStageToolPolicy aiStageToolPolicy);

    /**
     * 根据ID更新
     */
    int updateById(AiStageToolPolicy aiStageToolPolicy);

    /**
     * 根据阶段与能力更新
     */
    int updateByStageCodeAndCapability(AiStageToolPolicy aiStageToolPolicy);

    /**
     * 删除指定阶段的全部策略
     */
    int deleteByStageCode(String stageCode);

    /**
     * 查询全部策略（含 allowed=0 的行）
     */
    List<AiStageToolPolicy> queryAll();

    /**
     * 查询指定阶段的全部策略（含 allowed=0 的行）
     */
    List<AiStageToolPolicy> queryByStageCode(@Param("stageCode") String stageCode);

    /**
     * 查询指定阶段允许的能力白名单（只取 allowed=1）
     */
    List<String> queryAllowedCapabilities(@Param("stageCode") String stageCode);

}
