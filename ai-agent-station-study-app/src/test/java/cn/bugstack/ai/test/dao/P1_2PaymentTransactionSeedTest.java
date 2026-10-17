package cn.bugstack.ai.test.dao;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * P1-2：在 PostgreSQL 里建 {@code payment_transaction} 表并写入样例数据（并自检）。
 * <p>
 * <b>为什么用测试来建表</b>：本机没装 {@code psql} 客户端（只有 JDBC 驱动），而这张表是 P1-2
 * 第三个数据源的前置条件。把"执行 SQL 文件"做成一个可重跑的集成测试，比让每个人手敲一遍更可靠，
 * 也让这份 SQL 天然被验证过一次。
 * <p>
 * 属于集成测试（需要 PostgreSQL 15432），默认被 {@code app/pom.xml} 的 surefire excludes 排除，按需单跑：
 * <pre>mvn -o test -Dtest=P1_2PaymentTransactionSeedTest</pre>
 * 幂等：SQL 文件本身是"建表 IF NOT EXISTS + 种子 ON CONFLICT DO NOTHING"，可重复执行。
 *
 * @author miaowazhongzi
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class P1_2PaymentTransactionSeedTest {

    private static final String SQL_FILE = "docs/dev-ops/postgres/sql/upgrade-p1-2-payment-transaction.sql";

    @Resource(name = "pgVectorDataSource")
    private DataSource pgVectorDataSource;

    @Test
    public void seedPaymentTransactionTable() throws Exception {
        Path sqlPath = resolveSqlFile();
        String script = Files.readString(sqlPath, StandardCharsets.UTF_8);

        try (Connection connection = pgVectorDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : splitStatements(script)) {
                statement.execute(sql);
            }

            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) AS cnt, COUNT(*) FILTER (WHERE status = 'TIMEOUT') AS timeout_cnt "
                            + "FROM payment_transaction")) {
                Assert.assertTrue("应能查到结果", rs.next());
                Assert.assertEquals("样例数据应为 4 条", 4, rs.getInt("cnt"));
                Assert.assertEquals("其中应恰有 1 条支付超时（对应 NO20260327001）", 1, rs.getInt("timeout_cnt"));
            }

            try (ResultSet rs = statement.executeQuery(
                    "SELECT pay_channel, duration_ms, fail_code FROM payment_transaction "
                            + "WHERE order_no = 'NO20260327001'")) {
                Assert.assertTrue("NO20260327001 应有支付流水", rs.next());
                Assert.assertEquals("支付渠道应为 WECHAT", "WECHAT", rs.getString("pay_channel"));
                Assert.assertEquals("网关耗时应是 30012ms（订单 PAY_TIMEOUT 的跨源证据）",
                        30012, rs.getInt("duration_ms"));
                Assert.assertEquals("失败码应为 GW_TIMEOUT", "GW_TIMEOUT", rs.getString("fail_code"));
            }
        }
    }

    /**
     * 定位 SQL 文件：测试的工作目录是 app 模块，因此要往上找仓库根
     */
    private Path resolveSqlFile() {
        List<Path> candidates = List.of(
                Path.of("..", SQL_FILE),
                Path.of(SQL_FILE),
                Path.of("..", "..", SQL_FILE));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("找不到 SQL 文件（已尝试 " + candidates + "），"
                + "请在仓库根或 app 模块目录下运行；或按文件头注释手工执行该 SQL");
    }

    /**
     * 极简 SQL 切分：去掉纯注释行后按分号切（本文件不含带分号的字符串字面量）
     */
    private List<String> splitStatements(String script) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            if (!part.isBlank()) {
                statements.add(part.trim());
            }
        }
        return statements;
    }

}
