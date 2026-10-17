package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 告警接入请求（M8 D-1 的适配器）
 * <p>
 * 刻意做成 **Alertmanager 风格**（`startsAt` / `endsAt` / `labels`），因为这是业界最常见的告警 payload 形态；
 * 其它告警系统（Zabbix / 自研）只要能映射出这几个字段就能接进来。
 * <p>
 * <b>不建表、不落库</b>：本接口只做"告警 → 故障窗字段"的**纯映射**，返回值可直接拼进
 * {@code POST /api/v1/agent/auto_agent} 的请求体（字段名一一对应）。
 *
 * @author miaowazhongzi
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IncidentAlertRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 告警开始时间（RFC3339 / ISO-8601，必填）—— 对应故障窗 faultStart
     */
    private String startsAt;

    /**
     * 告警结束时间（可选）。为空表示告警仍在进行 → faultEnd 留空，由服务端按 now 推导
     */
    private String endsAt;

    /**
     * 上次故障结束时间（可选，非 Alertmanager 标准字段）—— 用于构造"避开余波"的前基线
     */
    private String prevFaultEnd;

    /**
     * 下次故障开始时间（可选，非 Alertmanager 标准字段）—— 仅当给出时才构造后基线
     */
    private String nextFaultStart;

    /**
     * 告警标签（Alertmanager 标准字段）；本适配器只取 {@code alertname}（回退 {@code summary}）作为故障标签
     */
    private Map<String, String> labels;

}
