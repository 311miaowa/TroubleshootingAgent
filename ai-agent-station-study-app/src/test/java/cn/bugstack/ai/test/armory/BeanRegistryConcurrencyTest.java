package cn.bugstack.ai.test.armory;

import cn.bugstack.ai.domain.agent.service.armory.registry.BeanRegistry;
import org.junit.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link BeanRegistry} 并发替换测试。
 * <p>
 * 背景：改造前，动态 Bean 的替换是「先 removeBeanDefinition 再 registerBeanDefinition」，
 * 两步之间存在一个空窗期，此刻读侧 getBean 会抛 NoSuchBeanDefinitionException，
 * 导致进行中的 Agent 会话随机失败。该窗口是微秒级的，靠人工并发压测无法稳定复现，
 * 因此用本测试在「大量替换 + 持续读取」的组合下把它逼出来。
 * <p>
 * 该测试同时验证「被替换掉的旧实例会被销毁」——旧实例若是 McpSyncClient，
 * 不销毁会泄漏其 stdio 子进程。
 * <p>
 * 注意：这是纯单元测试，不依赖数据库、Docker 或任何外部服务。
 */
public class BeanRegistryConcurrencyTest {

    /** 本测试会执行两万次注册，BeanRegistry 每次都会打一条 INFO 日志（约 2.8MB）。这里降到 WARN 保持输出可读。 */
    static {
        try {
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BeanRegistry.class);
            logger.setLevel(ch.qos.logback.classic.Level.WARN);
        } catch (Throwable ignored) {
            // 日志实现不是 logback 时静默跳过，不影响测试结论
        }
    }

    /** 模拟被动态装配的组件（如 McpSyncClient），AutoCloseable 以便验证销毁 */
    public static class DummyClient implements AutoCloseable {

        static final AtomicInteger CLOSED_COUNT = new AtomicInteger();

        @SuppressWarnings("unused")
        private final int version;

        public DummyClient(int version) {
            this.version = version;
        }

        @Override
        public void close() {
            CLOSED_COUNT.incrementAndGet();
        }
    }

    @Test
    public void concurrentReplaceShouldNeverExposeEmptyWindow() throws Exception {
        DummyClient.CLOSED_COUNT.set(0);

        // BeanRegistry 需要一个已激活（active）的 ApplicationContext 才能 getBean
        GenericApplicationContext applicationContext = new GenericApplicationContext();
        applicationContext.refresh();

        final BeanRegistry registry = new BeanRegistry(applicationContext);
        final String beanName = "probeClient";

        // 先注册一次，确保读侧开始前 Bean 已存在；
        // 否则读侧会把「尚未注册」误判为缺陷。
        registry.register(beanName, DummyClient.class, new DummyClient(-1));

        final int replacements = 20000;
        final int readerCount = 4;
        final AtomicInteger failures = new AtomicInteger();
        final AtomicReference<String> firstFailure = new AtomicReference<>();
        final AtomicBoolean writeFinished = new AtomicBoolean(false);
        final CountDownLatch startGate = new CountDownLatch(1);

        List<Thread> threads = new ArrayList<>();

        Thread writer = new Thread(() -> {
            awaitQuietly(startGate);
            for (int i = 0; i < replacements; i++) {
                try {
                    registry.register(beanName, DummyClient.class, new DummyClient(i));
                } catch (Throwable t) {
                    recordFailure(failures, firstFailure, t);
                }
            }
            writeFinished.set(true);
        }, "bean-registry-writer");
        threads.add(writer);

        for (int i = 0; i < readerCount; i++) {
            Thread reader = new Thread(() -> {
                awaitQuietly(startGate);
                while (!writeFinished.get()) {
                    try {
                        if (registry.getBean(beanName) == null) {
                            recordFailure(failures, firstFailure,
                                    new IllegalStateException("getBean 返回 null"));
                        }
                        if (!registry.containsBean(beanName)) {
                            recordFailure(failures, firstFailure,
                                    new IllegalStateException("containsBean 返回 false"));
                        }
                    } catch (Throwable t) {
                        recordFailure(failures, firstFailure, t);
                    }
                }
            }, "bean-registry-reader-" + i);
            threads.add(reader);
        }

        threads.forEach(Thread::start);
        startGate.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertTrue("替换全部完成后 Bean 应仍可读取", registry.containsBean(beanName));

        // 失败时把首个异常栈打出来，便于定位
        if (failures.get() != 0) {
            assertEquals("并发替换期间读侧出现异常，首个异常：" + System.lineSeparator() + firstFailure.get(),
                    0, failures.get());
        }
        assertNull(firstFailure.get());

        assertTrue("被替换掉的旧实例应被销毁（close 被调用），当前调用次数：" + DummyClient.CLOSED_COUNT.get(),
                DummyClient.CLOSED_COUNT.get() > 0);

        applicationContext.close();
    }

    private static void recordFailure(AtomicInteger failures, AtomicReference<String> firstFailure, Throwable t) {
        failures.incrementAndGet();
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        firstFailure.compareAndSet(null, writer.toString());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
