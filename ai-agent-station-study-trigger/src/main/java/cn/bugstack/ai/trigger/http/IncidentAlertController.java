package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.FaultWindowPayloadDTO;
import cn.bugstack.ai.api.dto.IncidentAlertRequestDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 告警接入（M8 D-1 适配器）
 * <p>
 * <b>它解决什么</b>：M8 的故障窗以"请求字段"为唯一真相。真实环境里这些字段来自**告警系统**，
 * 而告警系统各有各的 payload 形态。本接口把最常见的 Alertmanager 风格
 * （{@code {startsAt, endsAt, labels}}）归一成排障接口要的字段，**不落库、不改任何状态**。
 * <p>
 * <b>典型用法</b>（告警触发后由值班脚本/自动化调用）：
 * <pre>
 * POST /api/v1/incident/alert
 * { "startsAt": "2026-03-27T10:00:00+08:00",
 *   "endsAt":   "2026-03-27T10:30:00+08:00",
 *   "labels":   { "alertname": "限流抖动", "service": "group-buy" } }
 * →
 * { "usable": true, "faultStart": "2026-03-27T10:00:00+08:00",
 *   "faultEnd": "2026-03-27T10:30:00+08:00", "faultLabel": "限流抖动", "warnings": [] }
 * </pre>
 * 然后把返回里的 {@code faultStart/faultEnd/faultLabel} 拼进 {@code POST /api/v1/agent/auto_agent} 即可。
 * <p>
 * <b>状态码</b>：可构成故障窗（startsAt 解析成功）→ 200；否则 **400** 并在 body 里给出 warnings。
 *
 * @author miaowazhongzi
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/incident")
public class IncidentAlertController {

    /**
     * 告警 → 故障窗字段（纯映射，见 {@link AlertWindowMapper}）
     */
    @PostMapping("/alert")
    public ResponseEntity<FaultWindowPayloadDTO> alert(@RequestBody(required = false) IncidentAlertRequestDTO request) {
        FaultWindowPayloadDTO payload = AlertWindowMapper.map(request);
        if (!payload.isUsable()) {
            log.warn("告警接入失败（无法构成故障窗）：{}", payload.getWarnings());
            return ResponseEntity.badRequest().body(payload);
        }
        log.info("告警接入成功：faultStart={} faultEnd={} label={}",
                payload.getFaultStart(), payload.getFaultEnd(), payload.getFaultLabel());
        return ResponseEntity.ok(payload);
    }

}
