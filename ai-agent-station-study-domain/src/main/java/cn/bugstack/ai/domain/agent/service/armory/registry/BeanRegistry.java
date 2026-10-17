package cn.bugstack.ai.domain.agent.service.armory.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 动态 Bean 注册中心
 * <p>
 * 负责军备库（装配）流程中，基于 BeanDefinition 的动态 Bean 注册与替换。
 * <p>
 * 相比原先散落在 {@code AbstractArmorySupport} 中的实现，本类解决两个问题：
 * <ol>
 *     <li><b>锁不互斥</b>：原实现的 {@code synchronized} 加在抽象基类的实例方法上，
 *         而该基类被 ApiNode/ModelNode/ToolMcpNode/AdvisorNode/ClientNode/RootNode
 *         各自继承、是 6 个不同的 Spring Bean，实例锁各不互斥。
 *         这里改为集中到本单例组件的一把锁。</li>
 *     <li><b>替换空窗期</b>：{@code removeBeanDefinition} 与 {@code registerBeanDefinition}
 *         之间存在窗口，此刻读侧 {@code getBean} 会抛 NoSuchBeanDefinitionException。
 *         这里让写侧（register）与读侧（getBean）共用同一把锁，读侧永远看不到中间状态。</li>
 * </ol>
 * 说明：{@code removeBeanDefinition} 内部会触发
 * {@code resetBeanDefinition -> destroySingleton}，因此被替换掉的旧实例会被正确销毁
 * （例如旧的 McpSyncClient 会释放其 stdio 子进程）。
 *
 * @author miaowazhongzi
 */
@Slf4j
@Component
public class BeanRegistry {

    private final ApplicationContext applicationContext;

    private final DefaultListableBeanFactory beanFactory;

    /** 全局唯一锁：装配写侧与运行读侧共用 */
    private final ReentrantLock lock = new ReentrantLock();

    public BeanRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
    }

    /**
     * 注册（或原子替换）一个动态 Bean。
     *
     * @param beanName    Bean 名称
     * @param beanClass   Bean 类型（可为接口，如 McpSyncClient / ChatClient / Advisor）
     * @param beanInstance 已构建好的实例
     */
    public <T> void register(String beanName, Class<T> beanClass, T beanInstance) {
        lock.lock();
        try {
            BeanDefinitionBuilder beanDefinitionBuilder =
                    BeanDefinitionBuilder.genericBeanDefinition(beanClass, () -> beanInstance);
            BeanDefinition beanDefinition = beanDefinitionBuilder.getRawBeanDefinition();
            beanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);

            // 显式声明销毁方法：实例为 AutoCloseable 时调用 close()
            //（McpSyncClient 依赖此动作释放 stdio 子进程）；否则不声明，
            // 避免 Spring 去推断一个并不存在的销毁方法。
            if (beanInstance instanceof AutoCloseable) {
                beanDefinition.setDestroyMethodName("close");
            }

            // 若已存在则先移除，触发旧单例的销毁
            if (beanFactory.containsBeanDefinition(beanName)) {
                beanFactory.removeBeanDefinition(beanName);
            }

            beanFactory.registerBeanDefinition(beanName, beanDefinition);

            // 立即实例化，避免读侧在懒加载窗口内取不到该 Bean
            beanFactory.getBean(beanName);

            log.info("成功注册Bean: {}", beanName);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取 Bean。与 {@link #register} 共用同一把锁，保证不会读到"已移除但尚未注册"的中间状态。
     */
    @SuppressWarnings("unchecked")
    public <T> T getBean(String beanName) {
        lock.lock();
        try {
            return (T) applicationContext.getBean(beanName);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 判断 Bean 是否存在。用于调用方在"动态 Bean 可能因配置原因未注册"时做容错，
     * 避免因单个组件缺失导致整轮装配失败。
     */
    public boolean containsBean(String beanName) {
        lock.lock();
        try {
            return beanFactory.containsBean(beanName);
        } finally {
            lock.unlock();
        }
    }

}
