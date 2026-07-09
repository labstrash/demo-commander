package com.example.commander.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReportFrequencyTest {

    @Test
    void shouldGetDbCode() {
        assertThat(ReportFrequency.DAILY.dbCode()).isEqualTo("DAILY");
        assertThat(ReportFrequency.EVERY_30_MIN.dbCode()).isEqualTo("EVERY_30_MIN");
    }

    @Test
    void shouldCheckWindowTimeFrequency() {
        assertThat(ReportFrequency.ONE_TIME_PER_DAY.isWindowTimeFrequency()).isTrue();
        assertThat(ReportFrequency.FOUR_TIMES_PER_DAY.isWindowTimeFrequency()).isTrue();
        assertThat(ReportFrequency.EIGHT_TIMES_PER_DAY.isWindowTimeFrequency()).isTrue();

        assertThat(ReportFrequency.DAILY.isWindowTimeFrequency()).isFalse();
        assertThat(ReportFrequency.EVERY_30_MIN.isWindowTimeFrequency()).isFalse();
        assertThat(ReportFrequency.EVERY_1_HOUR.isWindowTimeFrequency()).isFalse();
        assertThat(ReportFrequency.EVERY_2_HOURS.isWindowTimeFrequency()).isFalse();
        assertThat(ReportFrequency.EVERY_4_HOURS.isWindowTimeFrequency()).isFalse();
        assertThat(ReportFrequency.SNAPSHOT.isWindowTimeFrequency()).isFalse();
    }

    @Test
    void shouldFromDbCode() {
        assertThat(ReportFrequency.fromDbCode("DAILY")).isEqualTo(ReportFrequency.DAILY);
        assertThat(ReportFrequency.fromDbCode("EVERY_30_MIN")).isEqualTo(ReportFrequency.EVERY_30_MIN);
    }

    @Test
    void shouldThrowFromDbCodeNull() {
        assertThatThrownBy(() -> ReportFrequency.fromDbCode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ReportFrequency code cannot be null");
    }

    @Test
    void shouldThrowFromDbCodeInvalid() {
        assertThatThrownBy(() -> ReportFrequency.fromDbCode("INVALID")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldFromConfig() {
        assertThat(ReportFrequency.fromConfig("DAILY")).isEqualTo(ReportFrequency.DAILY);
        assertThat(ReportFrequency.fromConfig("EVERY_30_MIN")).isEqualTo(ReportFrequency.EVERY_30_MIN);
    }

    @Test
    void shouldFromConfigWithKebabCase() {
        assertThat(ReportFrequency.fromConfig("every-30-min")).isEqualTo(ReportFrequency.EVERY_30_MIN);
        assertThat(ReportFrequency.fromConfig("one-time-per-day")).isEqualTo(ReportFrequency.ONE_TIME_PER_DAY);
        assertThat(ReportFrequency.fromConfig("four-times-per-day")).isEqualTo(ReportFrequency.FOUR_TIMES_PER_DAY);
    }

    @Test
    void shouldThrowFromConfigNull() {
        assertThatThrownBy(() -> ReportFrequency.fromConfig(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ReportFrequency value cannot be null");
    }

    @Test
    void shouldThrowFromConfigInvalid() {
        assertThatThrownBy(() -> ReportFrequency.fromConfig("invalid")).isInstanceOf(IllegalArgumentException.class);
    }
}
