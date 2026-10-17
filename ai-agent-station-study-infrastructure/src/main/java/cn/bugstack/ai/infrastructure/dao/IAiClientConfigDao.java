package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiClientConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI客户端统一关联配置表 DAO
 * @author miaowazhongzi
 * @description AI客户端统一关联配置表数据访问对象
 */
@Mapper
public interface IAiClientConfigDao {

    /**
     * 插入AI客户端配置
     * @param aiClientConfig AI客户端配置对象
     * @return 影响行数
     */
    int insert(AiClientConfig aiClientConfig);

    /**
     * 根据ID更新AI客户端配置
     * @param aiClientConfig AI客户端配置对象
     * @return 影响行数
     */
    int updateById(AiClientConfig aiClientConfig);

    /**
     * 根据源ID更新AI客户端配置
     * @param aiClientConfig AI客户端配置对象
     * @return 影响行数
     */
    int updateBySourceId(AiClientConfig aiClientConfig);

    /**
     * 根据ID删除AI客户端配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据源ID删除AI客户端配置
     * @param sourceId 源ID
     * @return 影响行数
     */
    int deleteBySourceId(String sourceId);

    /**
     * 根据ID查询AI客户端配置
     * @param id 主键ID
     * @return AI客户端配置对象
     */
    AiClientConfig queryById(Long id);

    /**
     * 根据源ID查询AI客户端配置
     * @param sourceId 源ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryBySourceId(String sourceId);

    /**
     * 根据目标ID查询AI客户端配置
     * @param targetId 目标ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryByTargetId(String targetId);

    /**
     * 根据源类型和源ID查询AI客户端配置
     * @param sourceType 源类型
     * @param sourceId 源ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryBySourceTypeAndId(@Param("sourceType") String sourceType, @Param("sourceId") String sourceId);

    /**
     * 根据目标类型和目标ID查询AI客户端配置
     * @param targetType 目标类型
     * @param targetId 目标ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryByTargetTypeAndId(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /**
     * 查询启用状态的AI客户端配置
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryEnabledConfigs();

    /**
     * 查询所有AI客户端配置
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryAll();

    /**
     * 查询"客户端组件配置"的整体版本号。
     * <p>
     * 取各配置表 update_time 的最大值（秒级时间戳）。用于配置热更新的变更检测，
     * 覆盖：api、model、tool_mcp、advisor、config、system_prompt、rag_order、client。
     * <p>
     * 注意：ai_agent_flow_config（阶段提示词）不在此查询内，由
     * {@link IAiAgentFlowConfigDao#queryMaxUpdateTime()} 单独查询，
     * 以便该表尚未补齐 update_time 列时不影响其余表的热更新。
     *
     * @return 最大更新时间（秒级时间戳）；无数据时返回 0
     */
    Long queryConfigVersion();

    /**
     * 查询"客户端组件配置"的总行数（上述 12 张配置表求和）。
     * <p>
     * 用途（P0-T2）：与 {@link #queryConfigVersion()} 组成<b>配置指纹</b>。
     * 单看 {@code MAX(update_time)} 检测不到删除：
     * <ul>
     *     <li>删掉"最新那一行"→ MAX 变小（甚至被别的表盖过而不变）；</li>
     *     <li>删掉任意一行（非最新）→ MAX 完全不变。</li>
     * </ul>
     * 加上行数后，"增删"必然改变指纹，"改"由 {@code update_time}（各表 DDL 均为
     * {@code ON UPDATE CURRENT_TIMESTAMP}）改变指纹，两者互补。
     *
     * @return 12 张配置表的总行数；无数据时返回 0
     */
    Long queryConfigRowTotal();

}