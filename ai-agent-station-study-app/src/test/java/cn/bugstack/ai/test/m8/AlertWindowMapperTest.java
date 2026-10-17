package cn.bugstack.ai.test.m8;

import cn.bugstack.ai.api.dto.FaultWindowPayloadDTO;
import cn.bugstack.ai.api.dto.IncidentAlertRequestDTO;
import cn.bugstack.ai.trigger.http.AlertWindowMapper;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

/**
 * M8 告警适配器单测（纯单测：只测"告警 → 故障窗字段"的映射规则，不起 HTTP）
 * <p>
 * 钉住的是**契约**：时间归一为 Asia/Shanghai 的 ISO-8601、label 取 alertname、
 * 缺 endsAt = 进行中（并告警）、startsAt 坏 = unusable（调用方返回 400）、其余字段坏了只忽略 + 告警。
 *
 * @author miaowazhongzi
 */
public class AlertWindowMapperTest {

    /** 正常告警：时间归一为 +08:00，label 取 labels.alertname */
    @Test
    public void map_normalAlert() {
        IncidentAlertRequestDTO request = IncidentAlertRequestDTO.builder()
                .startsAt("2026-03-27T02:00:00Z")   // = 10:00+08:00
                .endsAt("2026-03-27T02:30:00Z")     // = 10:30+08:00
                .labels(Map.of("alertname", "限流抖动", "service", "group-buy"))
                .build();

        FaultWindowPayloadDTO payload = AlertWindowMapper.map(request);

        Assert.assertTrue(payload.isUsable());
        Assert.assertEquals("2026-03-27T10:00:00+08:00", payload.getFaultStart());
        Assert.assertEquals("2026-03-27T10:30:00+08:00", payload.getFaultEnd());
        Assert.assertEquals("限流抖动", payload.getFaultLabel());
        Assert.assertTrue("正常告警不应有告警信息：" + payload.getWarnings(), payload.getWarnings().isEmpty());
    }

    /** 进行中的告警（没有 endsAt）：faultEnd 留空 + 明确告警 */
    @Test
    public void map_ongoingAlert() {
        IncidentAlertRequestDTO request = IncidentAlertRequestDTO.builder()
                .startsAt("2026-03-27T10:00:00+08:00")
                .build();

        FaultWindowPayloadDTO payload = AlertWindowMapper.map(request);

        Assert.assertTrue(payload.isUsable());
        Assert.assertNull(payload.getFaultEnd());
        Assert.assertTrue("应说明按进行中处理：" + payload.getWarnings(),
                payload.getWarnings().stream().anyMatch(w -> w.contains("故障进行中")));
    }

    /** startsAt 坏掉：unusable（Controller 会返回 400），并告警 */
    @Test
    public void map_badStartsAt_isUnusable() {
        IncidentAlertRequestDTO request = IncidentAlertRequestDTO.builder()
                .startsAt("not-a-time")
                .build();

        FaultWindowPayloadDTO payload = AlertWindowMapper.map(request);

        Assert.assertFalse(payload.isUsable());
        Assert.assertNull(payload.getFaultStart());
        Assert.assertTrue("应告警 startsAt 无法解析：" + payload.getWarnings(),
                payload.getWarnings().stream().anyMatch(w -> w.contains("startsAt 无法解析")));
    }

    /** 非关键字段坏了（prevFaultEnd）：只忽略 + 告警，主窗口仍可用 */
    @Test
    public void map_badOptionalField_isIgnored() {
        IncidentAlertRequestDTO request = IncidentAlertRequestDTO.builder()
                .startsAt("2026-03-27T10:00:00+08:00")
                .endsAt("2026-03-27T11:00:00+08:00")
                .prevFaultEnd("yesterday")
                .build();

        FaultWindowPayloadDTO payload = AlertWindowMapper.map(request);

        Assert.assertTrue(payload.isUsable());
        Assert.assertNull(payload.getPrevFaultEnd());
        Assert.assertTrue("应告警 prevFaultEnd 被忽略：" + payload.getWarnings(),
                payload.getWarnings().stream().anyMatch(w -> w.contains("prevFaultEnd 无法解析")));
    }

    /** 没有 labels：faultLabel 为空；label 回退到 summary；空 payload 不可用 */
    @Test
    public void map_labelFallback_andNullPayload() {
        Assert.assertNull(AlertWindowMapper.map(IncidentAlertRequestDTO.builder()
                .startsAt("2026-03-27T10:00:00+08:00").build()).getFaultLabel());

        Assert.assertEquals("服务超时", AlertWindowMapper.map(IncidentAlertRequestDTO.builder()
                .startsAt("2026-03-27T10:00:00+08:00")
                .labels(Map.of("summary", "服务超时"))
                .build()).getFaultLabel());

        FaultWindowPayloadDTO empty = AlertWindowMapper.map(null);
        Assert.assertFalse(empty.isUsable());
        Assert.assertTrue(empty.getWarnings().stream().anyMatch(w -> w.contains("payload 为空")));
    }

}
