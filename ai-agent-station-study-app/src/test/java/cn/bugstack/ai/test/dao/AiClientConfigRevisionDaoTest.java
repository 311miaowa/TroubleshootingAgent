package cn.bugstack.ai.test.dao;

import cn.bugstack.ai.config.AiClientConfigRefreshScheduler;
import cn.bugstack.ai.infrastructure.dao.IAiClientConfigDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import javax.sql.DataSource;

/**
 * P0-T2 回归（集成）：删除/软删都必须能被"配置指纹"发现。
 * <p>
 * 前置：MySQL 已启动（`ai-agent-station-study` 库已建表）。属于集成测试，
 * 默认被 `app/pom.xml` 的 surefire excludes 排除，按需单跑：
 * <pre>mvn -o test -Dtest=AiClientConfigRevisionDaoTest</pre>
 * <p>
 * <b>为什么用探针行而不是删真实配置</b>：探针写进 `ai_data_source_entity` 且 `ds_id='__p0_t2_probe__'`——
 * 所有读取都按真实 dsId 过滤，因此这行对运行时**完全不可见**；用例结束（含失败）都会把它清掉，
 * 绝不触碰任何真实配置行。
 * <p>
 * <b>它在钉什么</b>：
 * <ol>
 *     <li>软删（`status=0`）→ `update_time` 由 `ON UPDATE CURRENT_TIMESTAMP` 自动刷新 → 版本号变大 → 能检测；</li>
 *     <li>硬删（`DELETE`）→ 版本号**回退**（删的正是最新那行时）→ 旧逻辑的 `>` 比较会**静默漏掉**，
 *         而指纹里的"总行数"让检测依旧成立。</li>
 * </ol>
 *
 * @author miaowazhongzi
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class AiClientConfigRevisionDaoTest {

    /** 探针数据源的 dsId：不可能与真实配置冲突 */
    private static final String PROBE_DS_ID = "__p0_t2_probe__";

    private static final String PROBE_ENTITY_CODE = "probe";

    @Resource
    private IAiClientConfigDao clientConfigDao;

    @Resource(name = "mysqlDataSource")
    private DataSource mysqlDataSource;

    private JdbcTemplate jdbc;

    @Before
    public void setUp() {
        jdbc = new JdbcTemplate(mysqlDataSource);
        cleanProbe();
    }

    @After
    public void tearDown() {
        cleanProbe();
    }

    /** 基线可用：版本号与总行数都能查到（SQL 与映射正确） */
    @Test
    public void baseline_queriesAreUsable() {
        Long version = clientConfigDao.queryConfigVersion();
        Long rows = clientConfigDao.queryConfigRowTotal();
        Assert.assertNotNull(version);
        Assert.assertNotNull(rows);
        Assert.assertTrue("版本号应大于 0", version > 0);
        Assert.assertTrue("12 张配置表总行数应大于 0", rows > 0);
    }

    /**
     * 核心回归：软删 → 版本号变大（可检测）；再硬删 → 版本号**回退**（旧逻辑漏检），
     * 但指纹因"总行数"变化仍然不同（新逻辑检测得到）。
     */
    @Test
    public void softDeleteThenHardDelete_areBothDetectable() throws Exception {
        Long baselineVersion = clientConfigDao.queryConfigVersion();
        Long baselineRows = clientConfigDao.queryConfigRowTotal();

        // 1) 新增探针行（status=1，但对运行时不可见：没有数据源用这个 dsId）
        jdbc.update("INSERT INTO ai_data_source_entity "
                        + "(ds_id, entity_code, entity_name, physical_name, capability, query_template, status, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, NOW(), NOW())",
                PROBE_DS_ID, PROBE_ENTITY_CODE, "P0-T2 探针", "probe_table", "entity_query", "SELECT 1");
        Long insertedVersion = clientConfigDao.queryConfigVersion();
        Long insertedRows = clientConfigDao.queryConfigRowTotal();
        Assert.assertTrue("新增行应让版本号变大", insertedVersion > baselineVersion);
        Assert.assertEquals("新增行应让总行数 +1", baselineRows + 1, insertedRows.longValue());

        // 2) 软删（status=0）：update_time 由 ON UPDATE CURRENT_TIMESTAMP 自动刷新 → 版本号继续变大
        Thread.sleep(1100L); // update_time 是秒级精度，跨过一秒再改，避免同秒内看不出变化
        jdbc.update("UPDATE ai_data_source_entity SET status = 0 WHERE ds_id = ? AND entity_code = ?",
                PROBE_DS_ID, PROBE_ENTITY_CODE);
        Long softDeletedVersion = clientConfigDao.queryConfigVersion();
        Long softDeletedRows = clientConfigDao.queryConfigRowTotal();
        Assert.assertTrue("软删必须被检测到（版本号变大）", softDeletedVersion > insertedVersion);
        Assert.assertEquals("软删不改变行数", insertedRows.longValue(), softDeletedRows.longValue());

        // 3) 硬删：删掉的正是"最新那一行"，版本号因此回退 —— 这正是 P0-T2 的原始场景
        jdbc.update("DELETE FROM ai_data_source_entity WHERE ds_id = ? AND entity_code = ?",
                PROBE_DS_ID, PROBE_ENTITY_CODE);
        Long afterDeleteVersion = clientConfigDao.queryConfigVersion();
        Long afterDeleteRows = clientConfigDao.queryConfigRowTotal();

        Assert.assertEquals("删行后总行数回到基线", baselineRows.longValue(), afterDeleteRows.longValue());
        Assert.assertTrue("删掉最新行后版本号回退：旧逻辑的 > 比较会静默不重载",
                afterDeleteVersion < softDeletedVersion);
        Assert.assertNotEquals(
                "指纹必须变化，否则这次删除仍然会被漏掉："
                        + "旧逻辑 lastVersion=" + softDeletedVersion + " / 现版本=" + afterDeleteVersion,
                AiClientConfigRefreshScheduler.fingerprint(softDeletedVersion, softDeletedRows, 0L),
                AiClientConfigRefreshScheduler.fingerprint(afterDeleteVersion, afterDeleteRows, 0L));
    }

    /**
     * 只删"非最新"的行：版本号完全不变，只有总行数变 → 指纹仍然能发现（旧逻辑完全无感）
     */
    @Test
    public void deleteNonNewestRow_versionUnchanged_rowTotalCatchesIt() {
        Long baselineVersion = clientConfigDao.queryConfigVersion();
        Long baselineRows = clientConfigDao.queryConfigRowTotal();

        // 插入一个 update_time 明确早于当前最大值的探针行：它不是"最新行"，删它不会动 MAX
        jdbc.update("INSERT INTO ai_data_source_entity "
                        + "(ds_id, entity_code, entity_name, physical_name, capability, query_template, status, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, '2020-01-01 00:00:00', '2020-01-01 00:00:00')",
                PROBE_DS_ID, PROBE_ENTITY_CODE, "P0-T2 探针(旧)", "probe_table", "entity_query", "SELECT 1");
        Long insertedVersion = clientConfigDao.queryConfigVersion();
        Assert.assertEquals("插入旧时间戳的行不应改变版本号（MAX 不受影响）",
                baselineVersion.longValue(), insertedVersion.longValue());

        jdbc.update("DELETE FROM ai_data_source_entity WHERE ds_id = ? AND entity_code = ?",
                PROBE_DS_ID, PROBE_ENTITY_CODE);
        Long afterDeleteVersion = clientConfigDao.queryConfigVersion();
        Long afterDeleteRows = clientConfigDao.queryConfigRowTotal();

        Assert.assertEquals("版本号完全没变（旧逻辑 100% 漏检）",
                baselineVersion.longValue(), afterDeleteVersion.longValue());
        Assert.assertEquals("行数回到基线", baselineRows.longValue(), afterDeleteRows.longValue());
        Assert.assertNotEquals(
                "指纹必须变化（靠总行数）：插入前 " + AiClientConfigRefreshScheduler.fingerprint(baselineVersion, baselineRows, 0L)
                        + " vs 删除后 " + AiClientConfigRefreshScheduler.fingerprint(afterDeleteVersion, afterDeleteRows, 0L),
                AiClientConfigRefreshScheduler.fingerprint(insertedVersion, baselineRows + 1, 0L),
                AiClientConfigRefreshScheduler.fingerprint(afterDeleteVersion, afterDeleteRows, 0L));
    }

    private void cleanProbe() {
        jdbc.update("DELETE FROM ai_data_source_entity WHERE ds_id = ?", PROBE_DS_ID);
    }

}
