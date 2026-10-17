package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.ai.domain.agent.service.armory.registry.BeanRegistry;
import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;

/**
 * 装配支撑类
 *
 * @author miaowazhongzi
 * 2025/6/27 07:14
 */
public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> {

    private final Logger log = LoggerFactory.getLogger(AbstractArmorySupport.class);

    @Resource
    protected ApplicationContext applicationContext;

    @Resource
    protected ThreadPoolExecutor threadPoolExecutor;

    @Resource
    protected IAgentRepository repository;

    @Resource
    protected BeanRegistry beanRegistry;

    @Override
    protected void multiThread(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        // 缺省的
    }

    protected String beanName(String id) {
        return null;
    }

    protected String dataName() {
        return null;
    }

    /**
     * 通用的Bean注册方法
     * <p>
     * 委托给 {@link BeanRegistry}：写侧与读侧共用全局锁，消除替换空窗期；
     * 并对 AutoCloseable 实例显式声明 close() 销毁方法。
     *
     * @param beanName  Bean名称
     * @param beanClass Bean类型
     * @param <T>       Bean类型
     */
    protected <T> void registerBean(String beanName, Class<T> beanClass, T beanInstance) {
        beanRegistry.register(beanName, beanClass, beanInstance);
    }

    protected <T> T getBean(String beanName) {
        return beanRegistry.getBean(beanName);
    }

    /**
     * 判断动态 Bean 是否已注册。装配过程中若某组件因配置原因被跳过，
     * 调用方应据此容错，而不是直接 getBean 导致整轮装配失败。
     */
    protected boolean containsBean(String beanName) {
        return beanRegistry.containsBean(beanName);
    }

}
