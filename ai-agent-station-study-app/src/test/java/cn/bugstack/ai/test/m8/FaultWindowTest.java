package cn.bugstack.ai.test.m8;

import cn.bugstack.ai.domain.agent.model.valobj.datasource.BaselineComparison;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.FaultWindow;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.TimeRange;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * M8 故障窗与对称基线单测（纯单测：只做时间运算，不启动 Spring、不连数据源）
 * <p>
 * 钉住的是**语义**：余波必须避开、无上次故障时镜像构造并告警、无下次故障时不构造后基线、
 * 过短基线不产出、进行中按 now 截断、非法结束时间按零长度处理。
 * <p>
 * 注意：本模块 surefire 用 **JUnit 4** provider，必须是 {@code org.junit.Test} + {@code public void}。
 *
 * @author miaowazhongzi
 */
public class FaultWindowTest {

    private static final Instant NOW = Instant.parse("2026-03-27T12:00:00Z");

    // 故障 10:00~11:00（UTC），上次故障 08:00 结束，下次故障 12:00 开始
    private static final String START = "2026-03-27T10:00:00Z";
    private static final String END = "2026-03-27T11:00:00Z";
    private static final String PREV_END = "2026-03-27T08:00:00Z";
    private static final String NEXT_START = "2026-03-27T12:00:00Z";

    /** 不传 faultStart → 没有故障窗（行为回到旧逻辑，不抛异常） */
    @Test
    public void parse_withoutStart_returnsNull() {
        Assert.assertNull(FaultWindow.parse(null, END, PREV_END, NEXT_START, "x"));
        Assert.assertNull(FaultWindow.parse("   ", END, null, null, null));
    }

    /** 带时区偏移的 ISO-8601 能解析成正确瞬时（+08:00 的 10:00 = UTC 02:00） */
    @Test
    public void parse_handlesOffsetIso() {
        FaultWindow window = FaultWindow.parse("2026-03-27T10:00:00+08:00", null, null, null, "下单失败");

        Assert.assertNotNull(window);
        Assert.assertEquals(Instant.parse("2026-03-27T02:00:00Z"), window.getStart());
        Assert.assertEquals("下单失败", window.getLabel());
        Assert.assertFalse("进行中应产生告警", window.warnings(NOW).isEmpty());
    }

    /** 字段无法解析：不抛异常，记入告警（本次仍可用，因为 faultStart 是好的） */
    @Test
    public void parse_invalidField_recordsWarning() {
        FaultWindow window = FaultWindow.parse(START, END, "not-a-time", NEXT_START, null);

        Assert.assertNotNull(window);
        Assert.assertNull(window.getPrevFaultEnd());
        Assert.assertTrue("应告警 prevFaultEnd 无法解析：" + window.warnings(NOW),
                window.warnings(NOW).stream().anyMatch(w -> w.contains("prevFaultEnd 无法解析")));
    }

    /** 故障窗：给了结束时间就用；没给就用 now（进行中） */
    @Test
    public void faultRange_usesEndOrNow() {
        TimeRange withEnd = FaultWindow.parse(START, END, null, null, null).faultRange(NOW);
        Assert.assertEquals(Instant.parse(START), withEnd.getStart());
        Assert.assertEquals(Instant.parse(END), withEnd.getEnd());

        FaultWindow ongoing = FaultWindow.parse(START, null, null, null, null);
        Assert.assertTrue(ongoing.isOngoing());
        TimeRange ongoingRange = ongoing.faultRange(NOW);
        Assert.assertEquals(Instant.parse(START), ongoingRange.getStart());
        Assert.assertEquals(NOW, ongoingRange.getEnd());
        Assert.assertTrue(ongoing.warnings(NOW).stream().anyMatch(w -> w.contains("故障未给结束时间")));
    }

    /** 前基线：有上次故障结束 → [上次结束+10min, 故障开始−10min)，且**绝不等于**故障开始（避开余波） */
    @Test
    public void baselineBefore_excludesAftermath() {
        FaultWindow window = FaultWindow.parse(START, END, PREV_END, null, null);

        Optional<TimeRange> before = window.baselineBefore(NOW);

        Assert.assertTrue("前基线应可用", before.isPresent());
        Assert.assertEquals(Instant.parse("2026-03-27T08:10:00Z"), before.get().getStart());
        Assert.assertEquals(Instant.parse("2026-03-27T09:50:00Z"), before.get().getEnd());
        Assert.assertNotEquals("前基线结束不得等于故障开始（余波）",
                window.getStart(), before.get().getEnd());
    }

    /** 前基线：无上次故障结束 → 按故障窗长度镜像构造，并在 warnings 写明 */
    @Test
    public void baselineBefore_mirrorsWhenNoPrevFault() {
        FaultWindow window = FaultWindow.parse(START, END, null, null, null);

        Optional<TimeRange> before = window.baselineBefore(NOW);

        Assert.assertTrue("镜像前基线应可用", before.isPresent());
        // 故障长度 1h → [10:00−1h−10min, 10:00−10min) = [08:50, 09:50)
        Assert.assertEquals(Instant.parse("2026-03-27T08:50:00Z"), before.get().getStart());
        Assert.assertEquals(Instant.parse("2026-03-27T09:50:00Z"), before.get().getEnd());
        Assert.assertTrue("必须说明是镜像构造：" + window.warnings(NOW),
                window.warnings(NOW).stream().anyMatch(w -> w.contains("镜像")));
    }

    /** 前基线过短（上次故障结束离得太近）→ 不产出，并告警 */
    @Test
    public void baselineBefore_tooShort_isEmptyWithWarning() {
        FaultWindow window = FaultWindow.parse(START, END, "2026-03-27T09:55:00Z", null, null);

        Assert.assertTrue(window.baselineBefore(NOW).isEmpty());
        Assert.assertTrue("应告警前基线不可用：" + window.warnings(NOW),
                window.warnings(NOW).stream().anyMatch(w -> w.contains("前基线不可用")));
    }

    /** 后基线：只有给出下次故障开始时间才构造，且从故障结束 +10min 起 */
    @Test
    public void baselineAfter_requiresNextStart() {
        FaultWindow withNext = FaultWindow.parse(START, END, null, NEXT_START, null);
        Optional<TimeRange> after = withNext.baselineAfter(NOW);
        Assert.assertTrue(after.isPresent());
        Assert.assertEquals(Instant.parse("2026-03-27T11:10:00Z"), after.get().getStart());
        Assert.assertEquals(Instant.parse(NEXT_START), after.get().getEnd());

        FaultWindow withoutNext = FaultWindow.parse(START, END, null, null, null);
        Assert.assertTrue(withoutNext.baselineAfter(NOW).isEmpty());
        Assert.assertTrue("必须说明没构造后基线：" + withoutNext.warnings(NOW),
                withoutNext.warnings(NOW).stream().anyMatch(w -> w.contains("未构造后基线")));
    }

    /** 非法：结束早于开始 → 按零长度处理并告警（不产生负长度窗口） */
    @Test
    public void illegalEnd_isTreatedAsZeroLength() {
        FaultWindow window = FaultWindow.parse("2026-03-27T10:00:00Z", "2026-03-27T09:00:00Z", null, null, null);

        TimeRange range = window.faultRange(NOW);
        Assert.assertEquals(range.getStart(), range.getEnd());
        Assert.assertTrue(window.warnings(NOW).stream().anyMatch(w -> w.contains("早于 faultStart")));
    }

    /** comparisons：fault 在前，其次 baseline_before / baseline_after，且每段都带 phase 标签 */
    @Test
    public void comparisons_arePhaseOrdered() {
        FaultWindow window = FaultWindow.parse(START, END, PREV_END, NEXT_START, null);

        List<BaselineComparison> comparisons = window.comparisons(NOW);

        Assert.assertEquals(3, comparisons.size());
        Assert.assertEquals(BaselineComparison.PHASE_FAULT, comparisons.get(0).getPhase());
        Assert.assertEquals(BaselineComparison.PHASE_BASELINE_BEFORE, comparisons.get(1).getPhase());
        Assert.assertEquals(BaselineComparison.PHASE_BASELINE_AFTER, comparisons.get(2).getPhase());
        comparisons.forEach(c -> Assert.assertNotNull(c.getRange()));
    }

    /** describe：给提示词上下文块的单行描述，必须含故障窗与基线时间（机械事实） */
    @Test
    public void describe_containsWindows() {
        FaultWindow window = FaultWindow.parse(START, END, PREV_END, NEXT_START, "下单失败");

        String text = window.describe(NOW);

        Assert.assertTrue(text, text.contains("故障窗"));
        Assert.assertTrue(text, text.contains("下单失败"));
        Assert.assertTrue(text, text.contains("前基线"));
        Assert.assertTrue(text, text.contains("后基线"));
    }

}
