package cn.bugstack.ai.test.spring.ai;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.IExecuteStrategy;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;

/**
 * 四步执行链完整性真实测试（M1.2）
 * <p>
 * 真实调用 {@code autoAgentExecuteStrategy.execute()} 发一次排查请求（aiAgentId=4），
 * 验证"工具按阶段裁剪"生效后，四步链完整走完——日志应出现「阶段2: 精准任务执行」与
 * 「阶段3: 质量监督检查」（M0 回归里这两步因短路而缺失）。
 * <p>
 * 说明：本测试真实调用 LLM（DeepSeek，key 在 ai_client_api），会消耗 Token；
 * 同步调用 execute()，四步链任一步抛异常都会使测试失败；超时上限 3 分钟。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class FourStepChainTest {

    @Resource(name = "autoAgentExecuteStrategy")
    private IExecuteStrategy autoAgentExecuteStrategy;

    @Test(timeout = 180_000)
    public void test_fourStepChain_complete() throws Exception {
        ExecuteCommandEntity command = ExecuteCommandEntity.builder()
                .aiAgentId("4")
                .message("查询 group-buy-market 项目里哪些用户被限流了，并说明限流原因")
                .sessionId("m1-2-four-step-" + System.currentTimeMillis())
                .maxStep(3)
                .build();

        ResponseBodyEmitter emitter = new ResponseBodyEmitter();

        // 同步执行：四步链完整走完则正常返回，任一步异常则抛出
        autoAgentExecuteStrategy.execute(command, emitter);

        log.info("四步执行链完整执行完成（无异常）");
    }

}
