package cn.bugstack.ai.domain.agent.model.valobj.datasource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体元数据，值对象
 * <p>
 * 用于生成"描述型"工具（{@code describe_entity}）的输出：模型据此知道有哪些实体、
 * 各自的语义是什么、有哪些字段可用——补回"原始 ES 工具不再暴露后"失去的探索能力。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EntityMeta {

    /**
     * 数据源ID
     */
    private String dsId;

    /**
     * 实体编码
     */
    private String entityCode;

    /**
     * 实体名称
     */
    private String entityName;

    /**
     * 实体描述（会注入给模型，帮助其选对实体）
     */
    private String entityDesc;

    /**
     * 能力（取 {@code DataSourceCapability.key()}，均为只读）：
     * entity_meta / entity_query / log_search / anomaly_summary / cross_source / fault_window
     */
    private String capability;

    /**
     * 物理定位（表名 / 索引模式）
     */
    private String physicalName;

    /**
     * 时间字段名
     */
    private String timeField;

    /**
     * 字段字典
     */
    @Builder.Default
    private List<FieldMeta> fields = new ArrayList<>();

    /**
     * 字段元数据，值对象
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FieldMeta {

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
        private boolean timeField;

        /**
         * 是否可作过滤条件
         */
        private boolean filterable;

        /**
         * 样例值
         */
        private String sampleValue;

        /**
         * 是否需脱敏
         */
        private boolean sensitive;

    }

}
