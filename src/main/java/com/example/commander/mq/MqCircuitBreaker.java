package com.example.commander.mq;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Small hand-rolled circuit breaker gating {@link ResilientMqSender}'s calls to MQ, so a
 * sustained outage doesn't make every message in a chunk pay a fresh connection-timeout cost
 * before dead-lettering.
 *
 * <p>No half-open state machine beyond "has the cool-down window passed": once open, a call
 * after {@code openUntil} elapses is let through as a probe — success closes the breaker and
 * resets the failure count, failure re-opens it (pushing {@code openUntil} out again from
 * now, which is also how a repeatedly-failing probe keeps resetting the cool-down).
 *
 * <p>Threshold and cool-down duration are configured via {@link MqResilienceProperties} and
 * are placeholder defaults — sizing them needs real outage/recovery data, not available at
 * implementation time.
 */
@Component
public class MqCircuitBreaker {

    private final int failureThreshold;
    private final Duration coolDown;
    private final Clock clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant openUntil = Instant.MIN;

    public MqCircuitBreaker(MqResilienceProperties properties, Clock clock) {
        this.failureThreshold = properties.getBreakerFailureThreshold();
        this.coolDown = Duration.ofSeconds(properties.getBreakerCoolDownSeconds());
        this.clock = clock;
    }

    /**
     * Returns whether a send attempt should be allowed through right now.
     *
     * @return {@code true} if the breaker is closed, or open but the cool-down window has
     *     elapsed (letting a probe call through); {@code false} if genuinely open
     */
    public boolean allowRequest() {
        return !clock.instant().isBefore(openUntil);
    }

    /** Records a successful send — closes the breaker and resets the failure count. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntil = Instant.MIN;
    }

    /**
     * Records a send that exhausted every retry attempt and still failed. Opens the breaker
     * (or, if already at/above threshold, pushes {@code openUntil} out again) once
     * consecutive failures reach the configured threshold.
     */
    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntil = clock.instant().plus(coolDown);
        }
    }
}
