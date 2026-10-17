package cn.bugstack.ai.domain.agent.model.valobj.datasource;

import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一查询结果，值对象
 * <p>
 * <b>纪律</b>：
 * <ol>
 *     <li>{@code rawRef} 从第一天就返回——M3 证据面板要用它回指原文。
 *         晚加会迫使所有 Provider 回头改签名，所以本步即使无人消费也要留下字段。</li>
 *     <li>{@code rows} 出 Provider 之前必须是"已按字段字典裁剪 + 敏感字段已脱敏 + 已按 max_rows 截断"的。</li>
 *     <li>{@code rawRef} <b>不进 Prompt</b>，只回传给 UI/上层做证据引用。</li>
 * </ol>
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QueryResult {

    /**
     * 行级原文回指的保留键（M3）：注入到每一行 Map 里，供证据面板逐条回指原文。
     * <p>
     * 不进 {@code columns}（渲染列）、不进 Prompt，只随 SSE 事件给 UI。
     */
    public static final String RAW_REF_KEY = "_rawRef";

    /**
     * 数据源ID
     */
    private String dsId;

    /**
     * 实体编码
     */
    private String entityCode;

    /**
     * 列名（顺序与 rows 中的 key 顺序一致，供 UI 稳定渲染）
     */
    @Builder.Default
    private List<String> columns = new ArrayList<>();

    /**
     * 结果行（已裁剪、已脱敏）
     */
    @Builder.Default
    private List<Map<String, Object>> rows = new ArrayList<>();

    /**
     * 命中总数（Provider 无法给出准确值时为已返回行数）
     */
    private long total;

    /**
     * 是否因 max_rows 被截断
     */
    private boolean truncated;

    /**
     * 查询耗时（毫秒）
     */
    private long costMs;

    /**
     * 原文引用（给 UI 的证据指针，如 ES 文档ID / 订单主键）；不进 Prompt
     */
    private String rawRef;

    /**
     * 告警信息（如"字段被裁剪""结果被截断"），用于让模型知道自己的结论依据有多强
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * 空结果（Provider 早期返回时用，避免各实现各写一份）
     */
    public static QueryResult empty(String dsId, String entityCode) {
        return QueryResult.builder().dsId(dsId).entityCode(entityCode).build();
    }

    /**
     * 序列化为「给模型看」的 JSON（M3.2）：剥离 {@code rawRef} 与每行的 {@code _rawRef}。
     * <p>
     * 纪律：rawRef 只给 UI/证据面板，不进 Prompt——模型不需要"去哪看原文"的技术指针，
     * 把它塞进 Prompt 只会污染模型、还可能泄露内部结构。事件层（SSE）用完整 JSON（含 rawRef），
     * 模型层用本方法返回的剥离版。
     */
    public String toModelJson() {
        List<Map<String, Object>> cleanRows = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Map<String, Object> clean = new LinkedHashMap<>(row);
                clean.remove(RAW_REF_KEY);
                cleanRows.add(clean);
            }
        }
        QueryResult copy = QueryResult.builder()
                .dsId(dsId)
                .entityCode(entityCode)
                .columns(columns)
                .rows(cleanRows)
                .total(total)
                .truncated(truncated)
                .costMs(costMs)
                .rawRef(null)
                .warnings(warnings)
                .build();
        return JSON.toJSONString(copy);
    }

}
