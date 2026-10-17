package cn.bugstack.ai.infrastructure.gateway.datasource;

import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.FieldFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 模板渲染 + 通用过滤（P1-2）
 * <p>
 * <b>纪律（继承 M1）</b>：SQL 只能来自 {@code ai_data_source_entity.query_template} 的命名占位符，
 * 模型永远不能拼 SQL。本类把这个纪律再往前推一格——支持一个 <b>{@code ${filters}} 占位符</b>，
 * 把 M1 就定义好、却一直没接线的 {@link FieldFilter} 真正用起来：
 * <ul>
 *     <li>过滤字段必须在<b>字段字典</b>里存在且 {@code is_filterable=1}（白名单，来自 {@code ai_data_source_field}）；</li>
 *     <li>字段名还要满足标识符正则，<b>操作符</b>只能取 {@link FieldFilter.Operator} 枚举里的值
 *         （纵深防御：即使字典被写脏、或模型塞了怪东西，也拼不出注入）；</li>
 *     <li>过滤<b>值</b>一律走 {@code ?} 参数绑定，并按字段类型加显式类型转换
 *         （PG 里 {@code integer = '30012'} 会直接报错，必须 {@code ?::bigint}）；</li>
 *     <li>模板里没有 {@code ${filters}} 的实体，过滤条件会被忽略并<b>返回告警</b>（而不是静默丢弃）。</li>
 * </ul>
 * <p>
 * 占位符按<b>模板出现顺序</b>扫描替换，参数也按同一顺序收集——这样 {@code ${filters}} 放在模板哪个位置
 * 都能正确对位（例如 {@code ... <= ${end} ${filters} ORDER BY ... LIMIT ${limit}}）。
 *
 * @author miaowazhongzi
 */
public final class SqlTemplateRenderer {

    /**
     * 通用过滤占位符：模板里出现它才启用按字段过滤（常量定义在 domain 的 {@link EntityQuery}，
     * 因为"要不要把过滤能力暴露给模型"由 domain 的工具 schema 决定）
     */
    public static final String FILTERS_PLACEHOLDER = EntityQuery.FILTERS_PLACEHOLDER;

    private static final String START = "${start}";

    private static final String END = "${end}";

    private static final String ORDER_NO = "${orderNo}";

    private static final String LIMIT = "${limit}";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]*)}");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SqlTemplateRenderer() {
    }

    /**
     * 数据库方言：决定过滤条件的类型转换写法
     */
    public enum Dialect {
        MYSQL,
        POSTGRES
    }

    /**
     * 渲染结果
     */
    public static final class Rendered {

        private final String sql;

        private final List<Object> args;

        private final List<String> warnings;

        Rendered(String sql, List<Object> args, List<String> warnings) {
            this.sql = sql;
            this.args = args;
            this.warnings = warnings;
        }

        public String getSql() {
            return sql;
        }

        public List<Object> getArgs() {
            return args;
        }

        public Object[] getArgsArray() {
            return args.toArray();
        }

        public List<String> getWarnings() {
            return warnings;
        }

    }

    /**
     * 渲染模板：按出现顺序替换占位符，并收集参数
     *
     * @param template 查询模板（来自实体配置）
     * @param values   标量占位符取值，键形如 {@code ${start}}
     * @param filters  通用过滤条件（可空）
     * @param fields   该实体的字段字典（白名单与类型来源；可为 null，此时任何过滤都会被拒绝）
     * @param dialect  数据库方言
     */
    public static Rendered render(String template,
                                  Map<String, Object> values,
                                  List<FieldFilter> filters,
                                  Collection<EntityMeta.FieldMeta> fields,
                                  Dialect dialect) {
        if (template == null || template.isBlank()) {
            throw new IllegalStateException("实体缺少 query_template");
        }

        List<String> warnings = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        boolean hasFiltersPlaceholder = template.contains(FILTERS_PLACEHOLDER);

        // 模板没启用 ${filters}：过滤条件只能被忽略，且必须让调用方知道（不能静默丢弃）
        if (!hasFiltersPlaceholder && filters != null && !filters.isEmpty()) {
            warnings.add("该实体模板未启用通用过滤（缺少 " + FILTERS_PLACEHOLDER + " 占位符），已忽略过滤条件："
                    + filters.stream().map(FieldFilter::getField).toList());
        }

        StringBuilder sql = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(template);
        int cursor = 0;
        while (matcher.find()) {
            sql.append(template, cursor, matcher.start());
            cursor = matcher.end();
            String token = matcher.group(0);
            String name = matcher.group(1);

            if ("filters".equals(name)) {
                appendFilters(sql, args, warnings, filters, fields, dialect);
                continue;
            }
            if (!values.containsKey(token)) {
                // 未知占位符直接失败：宁可报错，也不要拼出一条语义不明的 SQL
                throw new IllegalStateException("query_template 含未支持的占位符：" + token);
            }
            sql.append('?');
            args.add(values.get(token));
        }
        sql.append(template.substring(cursor));

        return new Rendered(sql.toString(), args, warnings);
    }

    /**
     * 追加过滤谓词：{@code AND col <op> ? [::type]}，字段、操作符、类型都受白名单约束
     */
    private static void appendFilters(StringBuilder sql,
                                      List<Object> args,
                                      List<String> warnings,
                                      List<FieldFilter> filters,
                                      Collection<EntityMeta.FieldMeta> fields,
                                      Dialect dialect) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        Map<String, EntityMeta.FieldMeta> filterable = filterableFields(fields);
        for (FieldFilter filter : filters) {
            if (filter == null) {
                continue;
            }
            String field = filter.getField();
            String value = filter.getValue();
            if (field == null || field.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            if (!IDENTIFIER.matcher(field).matches()) {
                warnings.add("过滤字段名非法，已忽略：" + field);
                continue;
            }
            EntityMeta.FieldMeta meta = filterable.get(field);
            if (meta == null) {
                warnings.add("字段不可过滤或不在字段字典中，已忽略：" + field);
                continue;
            }

            FieldFilter.Operator operator = filter.getOperator() == null ? FieldFilter.Operator.EQ : filter.getOperator();
            String placeholder = castPlaceholder(meta.getFieldType(), dialect);
            switch (operator) {
                case EQ -> sql.append(" AND ").append(field).append(" = ").append(placeholder).append(' ');
                case NE -> sql.append(" AND ").append(field).append(" <> ").append(placeholder).append(' ');
                case GT -> sql.append(" AND ").append(field).append(" > ").append(placeholder).append(' ');
                case GTE -> sql.append(" AND ").append(field).append(" >= ").append(placeholder).append(' ');
                case LT -> sql.append(" AND ").append(field).append(" < ").append(placeholder).append(' ');
                case LTE -> sql.append(" AND ").append(field).append(" <= ").append(placeholder).append(' ');
                case LIKE -> sql.append(" AND ").append(field).append(" LIKE ").append(placeholder).append(' ');
                case IN -> {
                    String[] parts = value.split(",");
                    List<String> values = new ArrayList<>();
                    for (String part : parts) {
                        if (part != null && !part.isBlank()) {
                            values.add(part.trim());
                        }
                    }
                    if (values.isEmpty()) {
                        warnings.add("IN 过滤值为空，已忽略字段：" + field);
                        continue;
                    }
                    sql.append(" AND ").append(field).append(" IN (");
                    for (int i = 0; i < values.size(); i++) {
                        sql.append(i > 0 ? ", " : "").append(placeholder);
                        args.add(values.get(i));
                    }
                    sql.append(") ");
                    continue;
                }
                default -> {
                    warnings.add("不支持的操作符，已忽略字段：" + field + " / " + operator);
                    continue;
                }
            }
            args.add(value);
        }
    }

    private static Map<String, EntityMeta.FieldMeta> filterableFields(Collection<EntityMeta.FieldMeta> fields) {
        Map<String, EntityMeta.FieldMeta> map = new LinkedHashMap<>();
        if (fields == null) {
            return map;
        }
        for (EntityMeta.FieldMeta field : fields) {
            if (field == null || field.getFieldName() == null || !field.isFilterable()) {
                continue;
            }
            map.put(field.getFieldName(), field);
        }
        return map;
    }

    /**
     * 按字段类型生成"带类型转换的占位符"：字符串直接绑，其它类型显式转换
     */
    private static String castPlaceholder(String fieldType, Dialect dialect) {
        String type = fieldType == null ? "" : fieldType.trim().toLowerCase();
        if (dialect == Dialect.POSTGRES) {
            return switch (type) {
                case "integer", "int", "bigint", "smallint", "long" -> "?::bigint";
                case "decimal", "numeric", "double", "float", "number" -> "?::numeric";
                case "timestamp", "datetime", "date_time" -> "?::timestamp";
                case "date" -> "?::date";
                case "boolean", "bool" -> "?::boolean";
                // 字符串类：无需转换（显式 ::text 反而可能影响索引使用）
                default -> "?";
            };
        }
        return switch (type) {
            case "integer", "int", "bigint", "smallint", "long" -> "CAST(? AS SIGNED)";
            case "decimal", "numeric", "double", "float", "number" -> "CAST(? AS DECIMAL(20,6))";
            case "timestamp", "datetime", "date_time" -> "CAST(? AS DATETIME)";
            case "date" -> "CAST(? AS DATE)";
            case "boolean", "bool" -> "CAST(? AS UNSIGNED)";
            default -> "?";
        };
    }

    /**
     * 便捷方法：常用标量占位符一次性组装
     */
    public static Map<String, Object> values(Object start, Object end, Object orderNo, Object limit) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(START, start);
        values.put(END, end);
        values.put(ORDER_NO, orderNo);
        values.put(LIMIT, limit);
        return values;
    }

}
