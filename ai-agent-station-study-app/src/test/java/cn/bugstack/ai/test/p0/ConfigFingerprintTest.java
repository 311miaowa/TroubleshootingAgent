package cn.bugstack.ai.test.p0;

import cn.bugstack.ai.config.AiClientConfigRefreshScheduler;
import org.junit.Assert;
import org.junit.Test;

/**
 * P0-T2 回归：配置指纹必须能发现"删除"，且不依赖版本号单调递增。
 * <p>
 * 背景（原始 bug）：变更检测只看 {@code MAX(update_time)} 并用 {@code >} 比较，
 * 于是"删掉最新那行"（MAX 变小）与"删掉任意非最新行"（MAX 不变）都会**静默不重载**。
 * 修法是把"版本号"换成指纹 {@code 版本:行数:提示词版本} 并用<b>等值</b>比较。
 * <p>
 * 本测试<b>纯逻辑、无外部依赖</b>，钉住上面这条设计：如果以后有人把行数从指纹里去掉，
 * 或者把比较改回 {@code >}，这里的用例就会红。
 *
 * @author miaowazhongzi
 */
public class ConfigFingerprintTest {

    /** 同一个配置状态 → 指纹相同（否则每次轮询都会误触发热更新） */
    @Test
    public void sameState_producesSameFingerprint() {
        Assert.assertEquals(
                AiClientConfigRefreshScheduler.fingerprint(1789995144L, 57L, 1789995000L),
                AiClientConfigRefreshScheduler.fingerprint(1789995144L, 57L, 1789995000L));
    }

    /**
     * 核心回归：删掉一行后，即使 MAX(update_time) 完全没变（删的是非最新行），
     * 指纹也必须变化——否则这次删除永远不会被热更新感知。
     */
    @Test
    public void deleteWithUnchangedVersion_stillChangesFingerprint() {
        String before = AiClientConfigRefreshScheduler.fingerprint(1789995144L, 57L, 1789995000L);
        String afterDelete = AiClientConfigRefreshScheduler.fingerprint(1789995144L, 56L, 1789995000L);
        Assert.assertNotEquals("删行（版本不变）必须改变指纹", before, afterDelete);
    }

    /** 删掉"最新那行"→ 版本变小，指纹同样必须变化（原来的 > 比较会漏掉） */
    @Test
    public void deleteNewestRow_versionGoesBackwards_stillChangesFingerprint() {
        String before = AiClientConfigRefreshScheduler.fingerprint(1789995144L, 57L, 1789995000L);
        String afterDelete = AiClientConfigRefreshScheduler.fingerprint(1789990000L, 56L, 1789995000L);
        Assert.assertNotEquals("版本回退必须改变指纹（这正是 > 比较漏掉的场景）", before, afterDelete);
    }

    /** 软删 / 普通修改：update_time 由 ON UPDATE CURRENT_TIMESTAMP 自动刷新 → 版本变大，指纹变化 */
    @Test
    public void softDeleteOrUpdate_bumpsVersion_changesFingerprint() {
        String before = AiClientConfigRefreshScheduler.fingerprint(1789995144L, 57L, 1789995000L);
        String afterSoftDelete = AiClientConfigRefreshScheduler.fingerprint(1789995200L, 57L, 1789995000L);
        Assert.assertNotEquals("软删（仅 status 变化）必须改变指纹", before, afterSoftDelete);
    }

    /** 新增一行：行数与版本同时变，指纹变化 */
    @Test
    public void insert_changesFingerprint() {
        String before = AiClientConfigRefreshScheduler.fingerprint(1789995144L, 57L, 1789995000L);
        String afterInsert = AiClientConfigRefreshScheduler.fingerprint(1789995200L, 58L, 1789995000L);
        Assert.assertNotEquals(before, afterInsert);
    }

    /** 阶段提示词（ai_agent_flow_config）单独一路：它变化也要影响指纹 */
    @Test
    public void flowConfigVersionChange_changesFingerprint() {
        String before = AiClientConfigRefreshScheduler.fingerprint(1789995144L, 57L, 1789995000L);
        String afterPromptEdit = AiClientConfigRefreshScheduler.fingerprint(1789995144L, 57L, 1789996000L);
        Assert.assertNotEquals("改 step_prompt 必须改变指纹", before, afterPromptEdit);
    }

    /**
     * 容错：某个查询失败（返回 null，例如 ai_agent_flow_config 还没补 update_time 列）时，
     * 指纹仍可用且稳定——缺列本身不产生"每次轮询都变化"的指纹抖动。
     */
    @Test
    public void nullInputs_areTreatedAsZero_andStable() {
        Assert.assertEquals("0:0:0", AiClientConfigRefreshScheduler.fingerprint(null, null, null));
        Assert.assertEquals(
                AiClientConfigRefreshScheduler.fingerprint(null, null, null),
                AiClientConfigRefreshScheduler.fingerprint(null, null, null));
        // 缺列（null）与"真的为 0"视为同一状态，避免无意义的重复加载
        Assert.assertEquals(
                AiClientConfigRefreshScheduler.fingerprint(1789995144L, 57L, null),
                AiClientConfigRefreshScheduler.fingerprint(1789995144L, 57L, 0L));
    }

}
