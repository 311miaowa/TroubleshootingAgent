package cn.bugstack.ai.domain.agent.model.valobj.datasource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结构化字段过滤条件，值对象
 * <p>
 * 设计上刻意"不是裸字符串 SQL/DSL 片段"——模型只能填字段名、操作符和值，
 * 条件到查询语句的翻译由 Provider 用自己的 {@code query_template} 完成。
 * 这样"禁止模型拼查询语句"这条纪律是结构上成立的，而不是靠提示词约束。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FieldFilter {

    /**
     * 字段名（必须存在于 ai_data_source_field 字典中）
     */
    private String field;

    /**
     * 操作符
     */
    private Operator operator;

    /**
     * 比较值（字符串形式；类型转换由 Provider 按字典中的 field_type 完成）
     */
    private String value;

    public static FieldFilter eq(String field, String value) {
        return FieldFilter.builder().field(field).operator(Operator.EQ).value(value).build();
    }

    public static FieldFilter in(String field, String value) {
        return FieldFilter.builder().field(field).operator(Operator.IN).value(value).build();
    }

    /**
     * 支持的操作符（白名单，未知操作符一律拒绝）
     */
    public enum Operator {
        EQ, NE, GT, GTE, LT, LTE, LIKE, IN
    }

}
