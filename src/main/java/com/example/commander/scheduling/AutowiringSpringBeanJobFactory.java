package com.example.commander.scheduling;

import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;
import org.springframework.stereotype.Component;

/**
 * Quartz job factory that resolves job instances through Spring instead of raw reflection.
 *
 * <p>Quartz normally creates job instances via their default constructor, bypassing Spring's
 * dependency injection entirely. For job classes registered as Spring beans (e.g. via
 * {@code @Component}), this factory fetches the instance from the {@link ApplicationContext}
 * so constructor injection works as it does for any other Spring bean. Job classes that are
 * <em>not</em> Spring-managed fall back to Quartz's reflective instantiation followed by
 * field/setter autowiring, preserving support for {@code @Autowired} fields where used.
 */
@Component
public class AutowiringSpringBeanJobFactory extends SpringBeanJobFactory implements ApplicationContextAware {

    private ApplicationContext applicationContext;
    private AutowireCapableBeanFactory beanFactory;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.beanFactory = applicationContext.getAutowireCapableBeanFactory();
    }

    @Override
    protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
        Class<?> jobClass = bundle.getJobDetail().getJobClass();
        if (applicationContext.getBeanNamesForType(jobClass).length > 0) {
            return applicationContext.getBean(jobClass);
        }

        Object job = super.createJobInstance(bundle);
        beanFactory.autowireBean(job);
        return job;
    }
}
