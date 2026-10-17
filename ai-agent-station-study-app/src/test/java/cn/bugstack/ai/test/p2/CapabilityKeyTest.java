package cn.bugstack.ai.test.p2;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import org.junit.Assert;
import org.junit.Test;

/**
 * P2 能力枚举收敛单测
 * <p>
 * 钉住的是**数据库契约**：{@code ai_stage_tool_policy.capability} 与 {@code EntityMeta.capability}
 * 里存的是 {@link DataSourceCapability#key()} 这个**小写串**。之前工厂里另写了一份字符串常量，
 * 两边各改一遍极易漂移——现在只有枚举一处，这个用例就是它的护栏。
 *
 * @author miaowazhongzi
 */
public class CapabilityKeyTest {

    @Test
    public void keysMatchDatabaseContract() {
        Assert.assertEquals("log_search", DataSourceCapability.LOG_SEARCH.key());
        Assert.assertEquals("entity_query", DataSourceCapability.ENTITY_QUERY.key());
        Assert.assertEquals("entity_meta", DataSourceCapability.ENTITY_META.key());
        Assert.assertEquals("anomaly_summary", DataSourceCapability.ANOMALY_SUMMARY.key());
        Assert.assertEquals("cross_source", DataSourceCapability.CROSS_SOURCE.key());
        Assert.assertEquals("fault_window", DataSourceCapability.FAULT_WINDOW.key());
    }

    @Test
    public void newerCapabilitiesAreRegistered() {
        // P2 之前 cross_source / fault_window 只存在于工厂的字符串常量里；现在必须是枚举成员
        Assert.assertNotNull(DataSourceCapability.valueOf("CROSS_SOURCE"));
        Assert.assertNotNull(DataSourceCapability.valueOf("FAULT_WINDOW"));
        Assert.assertEquals("能力数量（新增能力时同步本断言与 ai_stage_tool_policy 的注释）",
                6, DataSourceCapability.values().length);
    }

}
