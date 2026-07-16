package com.example.commander.mq;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MqCircuitBreakerTest {

    private static final int THRESHOLD = 3;
    private static final long COOL_DOWN_SECONDS = 10;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
    private final MqCircuitBreaker breaker = new MqCircuitBreaker(properties(), clock);

    @Test
    void staysClosedBelowThreshold() {
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void opensAtThresholdAndSkipsRequestsWhileOpen() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.allowRequest())
                .describedAs("must genuinely skip, not just fail a retried attempt")
                .isFalse();
    }

    @Test
    void allowsAProbeAfterCoolDownElapses() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.allowRequest()).isFalse();

        clock.advanceTo(clock.instant().plusSeconds(COOL_DOWN_SECONDS));

        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void successfulProbeClosesTheBreakerAndResetsTheCount() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        clock.advanceTo(clock.instant().plusSeconds(COOL_DOWN_SECONDS));

        breaker.recordSuccess();

        assertThat(breaker.allowRequest()).isTrue();

        // counter reset - two more failures alone shouldn't reopen it
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void failedProbeResetsTheCoolDownWindow() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        clock.advanceTo(clock.instant().plusSeconds(COOL_DOWN_SECONDS));
        assertThat(breaker.allowRequest()).isTrue();

        breaker.recordFailure(); // the probe itself failed
        assertThat(breaker.allowRequest())
                .describedAs("a failed probe must re-open the breaker, not leave it closed")
                .isFalse();

        clock.advanceTo(clock.instant().plusSeconds(COOL_DOWN_SECONDS - 1));
        assertThat(breaker.allowRequest())
                .describedAs("cool-down must have restarted from the probe failure, not the original open")
                .isFalse();
    }

    private static MqResilienceProperties properties() {
        MqResilienceProperties properties = new MqResilienceProperties();
        properties.setBreakerFailureThreshold(THRESHOLD);
        properties.setBreakerCoolDownSeconds(COOL_DOWN_SECONDS);
        return properties;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceTo(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
