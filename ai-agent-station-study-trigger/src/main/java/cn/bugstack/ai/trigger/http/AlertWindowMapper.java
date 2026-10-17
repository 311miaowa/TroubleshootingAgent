package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.FaultWindowPayloadDTO;
import cn.bugstack.ai.api.dto.IncidentAlertRequestDTO;
import cn.bugstack.ai.domain.agent.service.datasource.RowTimes;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 告警 → 故障窗字段 的**纯映射**（M8 D-1 适配器）
 * <p>
 * <b>为什么单独抽出来</b>：映射规则（时间归一、缺字段降级、标签取哪个）是要被单测钉死的东西；
 * 放进 Controller 就只能靠起 HTTP 才能测。这里是纯静态函数，零依赖。
 * <p>
 * <b>时间口径</b>：接受 RFC3339 / ISO-8601（带偏移、带 Z、或裸日期时间）；
 * 输出统一归一为 **Asia/Shanghai** 的 `yyyy-MM-ddTHH:mm:ssXXX`，与 M8 设计文档 §5.1 的示例一致，
 * 可直接拼进排障请求。
 * <p>
 * <b>不猜</b>：`startsAt` 解析不出来 → `usable=false` + 告警（调用方应返回 400）；
 * 其余字段解析失败只忽略并告警，不影响主窗口。
 *
 * @author miaowazhongzi
 */
public final class AlertWindowMapper {

    /**
     * 输出格式：带偏移的 ISO-8601（如 2026-03-27T10:00:00+08:00）
     */
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private AlertWindowMapper() {
    }

    /**
     * 把 Alertmanager 风格的告警映射成故障窗字段
     *
     * @param request 告警 payload（可为 null → usable=false）
     */
    public static FaultWindowPayloadDTO map(IncidentAlertRequestDTO request) {
        List<String> warnings = new ArrayList<>();
        if (request == null) {
            warnings.add("告警 payload 为空：无法生成故障窗");
            return FaultWindowPayloadDTO.builder().usable(false).warnings(warnings).build();
        }

        String faultStart = normalize("startsAt", request.getStartsAt(), warnings);
        String faultEnd = normalize("endsAt", request.getEndsAt(), warnings);
        String prevFaultEnd = normalize("prevFaultEnd", request.getPrevFaultEnd(), warnings);
        String nextFaultStart = normalize("nextFaultStart", request.getNextFaultStart(), warnings);
        boolean usable = faultStart != null;

        if (!usable) {
            warnings.add("startsAt 缺失或无法解析：无法生成故障窗（请检查告警 payload 的时间格式）");
        }
        if ((request.getEndsAt() == null || request.getEndsAt().isBlank()) && usable) {
            warnings.add("未提供 endsAt：按「故障进行中」处理（faultEnd 留空，服务端按当前时间推导）");
        }

        return FaultWindowPayloadDTO.builder()
                .usable(usable)
                .faultStart(faultStart)
                .faultEnd(faultEnd)
                .prevFaultEnd(prevFaultEnd)
                .nextFaultStart(nextFaultStart)
                .faultLabel(labelOf(request.getLabels()))
                .warnings(warnings)
                .build();
    }

    /**
     * 时间归一：沿用 {@link RowTimes#parseText(String)} 的口径（带偏移 ISO / 裸 ISO 按 Asia/Shanghai / epoch）
     */
    private static String normalize(String field, String text, List<String> warnings) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Instant at = RowTimes.parseText(text);
        if (at == null) {
            warnings.add(field + " 无法解析（期望 RFC3339/ISO-8601，如 2026-03-27T10:00:00+08:00），已忽略");
            return null;
        }
        return OUT.format(at.atZone(RowTimes.BIZ_ZONE));
    }

    /**
     * 故障标签：优先 {@code labels.alertname}，回退 {@code labels.summary}（Alertmanager 常见字段）
     */
    private static String labelOf(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        String alertname = labels.get("alertname");
        if (alertname != null && !alertname.isBlank()) {
            return alertname;
        }
        String summary = labels.get("summary");
        return summary == null || summary.isBlank() ? null : summary;
    }

}
