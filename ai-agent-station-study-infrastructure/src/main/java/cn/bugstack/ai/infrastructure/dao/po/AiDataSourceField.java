package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据源字段字典表
 *
 * @author miaowazhongzi
 * @description 数据源字段字典表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiDataSourceField {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 数据源ID
     */
    private String dsId;

    /**
     * 实体编码
     */
    private String entityCode;

    /**
     * 字段名
     */
    private String fieldName;

    /**
     * 中文名
     */
    private String fieldLabel;

    /**
     * 字段类型
     */
    private String fieldType;

    /**
     * 是否时间字段
     */
    private Integer isTimeField;

    /**
     * 是否可过滤
     */
    private Integer isFilterable;

    /**
     * 样例值
     */
    private String sampleValue;

    /**
     * 是否需脱敏
     */
    private Integer sensitive;

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
