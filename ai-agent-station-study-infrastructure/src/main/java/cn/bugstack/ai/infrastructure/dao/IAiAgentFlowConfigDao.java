package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiAgentFlowConfig;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 智能体-客户端关联表 DAO
 * @author miaowazhongzi
 * @description 智能体-客户端关联表数据访问对象
 */
@Mapper
public interface IAiAgentFlowConfigDao {

    /**
     * 插入智能体-客户端关联配置
     * @param aiAgentFlowConfig 智能体-客户端关联配置对象
     * @return 影响行数
     */
    int insert(AiAgentFlowConfig aiAgentFlowConfig);

    /**
     * 根据ID更新智能体-客户端关联配置
     * @param aiAgentFlowConfig 智能体-客户端关联配置对象
     * @return 影响行数
     */
    int updateById(AiAgentFlowConfig aiAgentFlowConfig);

    /**
     * 根据ID删除智能体-客户端关联配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(String id);

    /**
     * 根据智能体ID删除关联配置
     * @param agentId 智能体ID
     * @return 影响行数
     */
    int deleteByAgentId(String agentId);

    /**
     * 根据ID查询智能体-客户端关联配置
     * @param id 主键ID
     * @return 智能体-客户端关联配置对象
     */
    AiAgentFlowConfig queryById(String id);

    /**
     * 根据智能体ID查询关联配置列表
     * @param agentId 智能体ID
     * @return 智能体-客户端关联配置列表
     */
    List<AiAgentFlowConfig> queryByAgentId(String agentId);

    /**
     * 根据客户端ID查询关联配置列表
     * @param clientId 客户端ID
     * @return 智能体-客户端关联配置列表
     */
    List<AiAgentFlowConfig> queryByClientId(String clientId);

    /**
     * 根据智能体ID和客户端ID查询关联配置
     * @param agentId 智能体ID
     * @param clientId 客户端ID
     * @return 智能体-客户端关联配置对象
     */
    AiAgentFlowConfig queryByAgentIdAndClientId(String agentId, String clientId);

    /**
     * 查询所有智能体-客户端关联配置
     * @return 智能体-客户端关联配置列表
     */
    List<AiAgentFlowConfig> queryAll();

    /**
     * 查询本表的最大更新时间（秒级时间戳）。
     * <p>
     * 各阶段提示词（step_prompt）存放在本表，配置热更新需要感知其变更。
     * 本表此前只有 create_time，已通过
     * docs/dev-ops/mysql/sql/upgrade-m0-1-flow-config-update-time.sql 补充 update_time 列。
     * <p>
     * 若该列尚未补齐，本查询会抛异常；调用方（定时刷新器）已做独立异常隔离，
     * 不会影响其它配置表的热更新。
     *
     * @return 最大更新时间（秒级时间戳）；无数据时返回 0
     */
    Long queryMaxUpdateTime();

}