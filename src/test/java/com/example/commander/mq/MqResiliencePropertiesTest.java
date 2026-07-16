package com.example.commander.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.example.commander.mq.MqResilienceProperties.Backoff;
import com.example.commander.mq.MqResilienceProperties.BackoffTier;
import java.util.List;
import org.junit.jupiter.api.Test;

class MqResiliencePropertiesTest {

    @Test
    void reportTypeListedInATierReturnsThatTiersBackoff() {
        MqResilienceProperties properties = new MqResilienceProperties();
        properties.setDeadLetterRetryBackoffDefault(new Backoff(60, 3600));
        properties.setDeadLetterRetryBackoffTiers(List.of(tier(15, 300, "CAMT052B", "CAMT052BT")));

        Backoff backoff = properties.getDeadLetterRetryBackoff("CAMT052B");

        assertThat(backoff.getBaseSeconds()).isEqualTo(15);
        assertThat(backoff.getMaxSeconds()).isEqualTo(300);
    }

    @Test
    void reportTypeNotListedInAnyTierFallsBackToTheDefault() {
        MqResilienceProperties properties = new MqResilienceProperties();
        properties.setDeadLetterRetryBackoffDefault(new Backoff(60, 3600));
        properties.setDeadLetterRetryBackoffTiers(List.of(tier(15, 300, "CAMT052B")));

        Backoff backoff = properties.getDeadLetterRetryBackoff("CAMT054C");

        assertThat(backoff.getBaseSeconds()).isEqualTo(60);
        assertThat(backoff.getMaxSeconds()).isEqualTo(3600);
    }

    @Test
    void noTiersConfiguredAlwaysReturnsTheDefault() {
        MqResilienceProperties properties = new MqResilienceProperties();
        properties.setDeadLetterRetryBackoffDefault(new Backoff(60, 3600));

        Backoff backoff = properties.getDeadLetterRetryBackoff("CAMT054C");

        assertThat(backoff.getBaseSeconds()).isEqualTo(60);
        assertThat(backoff.getMaxSeconds()).isEqualTo(3600);
    }

    @Test
    void reportTypeListedInMoreThanOneTierFailsFast() {
        MqResilienceProperties properties = new MqResilienceProperties();
        properties.setDeadLetterRetryBackoffTiers(List.of(tier(15, 300, "CAMT052B"), tier(120, 7200, "CAMT052B")));

        assertThatThrownBy(() -> properties.getDeadLetterRetryBackoff("CAMT052B"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAMT052B")
                .hasMessageContaining("more than one");
    }

    private static BackoffTier tier(long baseSeconds, long maxSeconds, String... reportTypes) {
        BackoffTier tier = new BackoffTier();
        tier.setBaseSeconds(baseSeconds);
        tier.setMaxSeconds(maxSeconds);
        tier.setReportTypes(List.of(reportTypes));
        return tier;
    }
}
