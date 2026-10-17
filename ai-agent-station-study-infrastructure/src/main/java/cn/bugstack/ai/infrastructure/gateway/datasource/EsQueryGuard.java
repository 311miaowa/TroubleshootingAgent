package cn.bugstack.ai.infrastructure.gateway.datasource;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ES 查询守卫——{@link SqlTemplateRenderer} 在 Elasticsearch 侧的<b>对等物</b>。
 * <p>
 * <b>为什么需要它</b>：SQL 侧有"标识符正则 + 字段字典白名单"，ES 侧此前没有对等防护。
 * {@code ElasticsearchProvider} 把两个来自库表的值直接用于请求：
 * <ul>
 *     <li>{@code entity_config.physical_name} 直接拼进 URL 路径（{@code /{physicalName}/_search}）；</li>
 *     <li>{@code entity_config.query_template} 渲染后的 DSL 直接作为请求体。</li>
 * </ul>
 * 两者都来自 {@code ai_data_source_entity}，改库即可影响发往 ES 的请求。本类把这两个口子
 * 收敛成<b>显式白名单 + 标识符正则</b>，与 SQL 侧同一纪律。
 * <p>
 * <b>已确认的现状（不夸大也不缩小）</b>：
 * <ul>
 *     <li>请求<b>方法与路径是硬编码</b>的（{@code POST} + {@code /{index}/_search}），
 *         因此应用内到不了 {@code _bulk} / {@code _delete_by_query} 等写端点——这部分本来就没问题；</li>
 *     <li>真正的口子是：① {@code physical_name} 未校验（可含 {@code /}、空白、控制字符，能改变请求路径）；
 *         ② DSL 渲染结果未校验（模板可含 {@code script} 等本想只读却具副作用/高开销的构造）。</li>
 * </ul>
 * <p>
 * <b>边界</b>：本类<b>只做静态白名单校验</b>，不是权限系统，也<b>不能</b>替代 ES 服务端的安全特性
 * （凭据只读、角色权限）。服务端仍是最终强制点。
 *
 * @author miaowazhongzi
 */
public final class EsQueryGuard {

    private EsQueryGuard() {
    }

    /** 与 {@code ai_data_source_entity.physical_name} 的列宽一致（varchar(128)） */
    public static final int MAX_PHYSICAL_NAME_LENGTH = 128;

    /**
     * 索引/索引模式标识符。
     * <p>
     * 允许：小写字母、数字、{@code . _ -}、以及通配 {@code *}（真实配置是 {@code group-buy-market-log-*}）。
     * 显式拒绝：{@code /}、{@code \}、{@code :}、空白、{@code %}、{@code ,}、{@code #}、{@code ?}、
     * 大写字母——它们要么能改变请求路径，要么是 ES 多目标/查询串语法。
     * <p>
     * 额外禁止：以 {@code _} 或 {@code .} 开头（ES 保留索引名形如 {@code _all} / {@code .kibana}），
     * 以及裸 {@code *}（等于放开所有索引）。
     */
    private static final Pattern PHYSICAL_NAME_PATTERN = Pattern.compile("^(?!_)(?!\\.)[a-z0-9*][a-z0-9._*-]*$");

    /**
     * DSL 顶层允许的键。
     * <p>
     * 用白名单而非黑名单：ES 的只读/写构造都在不断增长，白名单在"新增构造"时是保守失败（拒），
     * 黑名单则是乐观放行。
     */
    private static final Set<String> ALLOWED_TOP_LEVEL_KEYS = Set.of(
            "query", "size", "from", "sort", "_source", "aggs", "aggregations",
            "track_total_hits", "highlight", "collapse", "post_filter", "search_after",
            "min_score", "timeout", "terminate_after", "indices_boost", "stored_fields",
            "docvalue_fields", "profile", "explain",
            "seq_no_primary_term", "version", "stats", "suggest", "rescore", "knn", "pit");

    /**
     * 任意层级都禁止出现的键——只读检索不该需要它们。
     * <p>
     * 这些键要么能执行脚本（{@code script} / {@code script_fields} / {@code runtime_mappings}），
     * 要么是写入/更新语义（{@code doc} / {@code upsert} / {@code update} / {@code _update}），
     * 要么会触发写管线或额外抓取（{@code pipeline} / {@code _source_includes} 之外的外呼构造）。
     */
    private static final Set<String> FORBIDDEN_KEYS_ANYWHERE = Set.of(
            "script", "script_fields", "runtime_mappings",
            "doc", "doc_as_upsert", "upsert", "update", "_update", "pipeline");

    /** size/from 硬上限，防止单次检索拖垮数据源 */
    public static final int MAX_SIZE = 10000;

    /**
     * 校验索引/索引模式标识符。
     *
     * @return 合法返回 {@code null}；不合法返回人类可读的原因（用于写进 {@code warnings}）
     */
    public static String validatePhysicalName(String physicalName) {
        if (physicalName == null || physicalName.isBlank()) {
            return "物理索引名为空";
        }
        if (physicalName.length() > MAX_PHYSICAL_NAME_LENGTH) {
            return "物理索引名超长（>" + MAX_PHYSICAL_NAME_LENGTH + "）";
        }
        if ("*".equals(physicalName)) {
            return "物理索引名不允许为裸通配 *（等于放开全部索引）";
        }
        if (!PHYSICAL_NAME_PATTERN.matcher(physicalName).matches()) {
            return "物理索引名不符合标识符白名单（仅允许小写字母/数字/._-/*，且不得以 _ 或 . 开头）："
                    + sanitizeForMessage(physicalName);
        }
        return null;
    }

    /**
     * 校验渲染后的 {@code _search} DSL。
     * <p>
     * 做三件事：① 必须是合法 JSON 对象；② 顶层键必须全部在白名单内；③ 任意层级不得出现被禁键。
     *
     * @return 合法返回 {@code null}；不合法返回原因
     */
    public static String validateSearchBody(String body) {
        if (body == null || body.isBlank()) {
            return "查询体为空";
        }

        JSONObject root;
        try {
            Object parsed = JSON.parse(body);
            if (!(parsed instanceof JSONObject)) {
                return "查询体必须是 JSON 对象";
            }
            root = (JSONObject) parsed;
        } catch (Exception e) {
            return "查询体不是合法 JSON：" + e.getMessage();
        }

        for (String key : root.keySet()) {
            if (!ALLOWED_TOP_LEVEL_KEYS.contains(key)) {
                return "查询体含未授权的顶层键：" + sanitizeForMessage(key);
            }
        }

        Set<String> forbidden = new LinkedHashSet<>();
        collectForbiddenKeys(root, forbidden);
        if (!forbidden.isEmpty()) {
            return "查询体含被禁键（只读检索不允许）：" + String.join(", ", forbidden);
        }

        String sizeError = validateBound(root, "size");
        if (sizeError != null) {
            return sizeError;
        }
        return validateBound(root, "from");
    }

    /** 校验 size / from 为非负且不超上限 */
    private static String validateBound(JSONObject root, String key) {
        Object raw = root.get(key);
        if (raw == null) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return key + " 必须是整数：" + sanitizeForMessage(String.valueOf(raw));
        }
        if (value < 0) {
            return key + " 不允许为负：" + value;
        }
        if (value > MAX_SIZE) {
            return key + " 超上限 " + MAX_SIZE + "：" + value;
        }
        return null;
    }

    /** 递归收集任意层级的被禁键 */
    private static void collectForbiddenKeys(Object node, Set<String> out) {
        if (node instanceof JSONObject obj) {
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                if (FORBIDDEN_KEYS_ANYWHERE.contains(entry.getKey())) {
                    out.add(entry.getKey());
                }
                collectForbiddenKeys(entry.getValue(), out);
            }
        } else if (node instanceof JSONArray array) {
            for (Object item : array) {
                collectForbiddenKeys(item, out);
            }
        }
    }

    /**
     * 清洗即将写进日志/告警的不可信输入：控制字符与换行会污染日志行，做最小替换。
     */
    private static String sanitizeForMessage(String value) {
        if (value == null) {
            return "null";
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "?");
        return cleaned.length() > 60 ? cleaned.substring(0, 60) + "…" : cleaned;
    }

}
