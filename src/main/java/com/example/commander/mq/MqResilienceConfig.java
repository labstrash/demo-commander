package com.example.commander.mq;

import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Wires the framework pieces {@link ResilientMqSender}/{@link MqCircuitBreaker} build on:
 * the {@code RetryTemplate} used programmatically (not {@code @Retryable} — see the MQ
 * resilience decisions for why: a writer's own {@code write()} calling its own send logic
 * is exactly the self-invocation trap the annotation's AOP proxy can't intercept), and a
 * {@link Clock} bean so the breaker's cool-down window is testable without real sleeps.
 */
@Configuration
public class MqResilienceConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RetryTemplate mqRetryTemplate(MqResilienceProperties properties) {
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                properties.getRetryMaxAttempts(), Map.of(TransientMqFailureException.class, true));

        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(properties.getRetryBackoffMs());

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        return retryTemplate;
    }
}
