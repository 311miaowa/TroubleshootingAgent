package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 告警接入的返回（M8 D-1 适配器）
 * <p>
 * 字段名与 {@link AutoAgentRequestDTO} 的故障窗字段**一一对应**，可以直接拼进排障请求：
 * <pre>
 * { "aiAgentId": "4", "message": "…", "faultStart": "…", "faultEnd": "…", "faultLabel": "…" }
 * </pre>
 *
 * @author miaowazhongzi
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FaultWindowPayloadDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否可构成故障窗（startsAt 解析成功即为 true）；false 时调用方应视为 400
     */
    private boolean usable;

    /**
     * 故障开始（已归一为 Asia/Shanghai 的 ISO-8601，如 2026-03-27T10:00:00+08:00）
     */
    private String faultStart;

    /**
     * 故障结束（可为空 = 进行中）
     */
    private String faultEnd;

    /**
     * 上次故障结束（可为空）
     */
    private String prevFaultEnd;

    /**
     * 下次故障开始（可为空）
     */
    private String nextFaultStart;

    /**
     * 故障标签（取自 labels.alertname，回退 labels.summary）
     */
    private String faultLabel;

    /**
     * 降级/忽略说明（字段缺失或无法解析时给出，调用方应原样展示给操作者）
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

}
