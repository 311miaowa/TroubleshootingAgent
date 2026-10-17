package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据源实体配置表
 *
 * @author miaowazhongzi
 * @description 数据源实体配置表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiDataSourceEntity {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 数据源ID
     */
    private String dsId;

    /**
     * 实体编码(order/log)
     */
    private String entityCode;

    /**
     * 实体名称
     */
    private String entityName;

    /**
     * 实体描述(注入给模型)
     */
    private String entityDesc;

    /**
     * 物理定位(表名/索引模式)
     */
    private String physicalName;

    /**
     * 能力(entity_query/log_search)，均为只读
     */
    private String capability;

    /**
     * 参数化查询模板(禁止模型拼查询语句)
     */
    private String queryTemplate;

    /**
     * 状态(0:禁用,1:启用)
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
