package com.example.commander.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReportWindowTest {

    @Test
    void shouldCreateReportWindow() {
        Instant start = Instant.now();
        Instant end = start.plusSeconds(3600);

        ReportWindow window = new ReportWindow(start, end);

        assertThat(window.windowStartUtc()).isEqualTo(start);
        assertThat(window.windowEndUtc()).isEqualTo(end);
    }

    @Test
    void shouldThrowWhenStartAfterEnd() {
        Instant end = Instant.now();
        Instant start = end.plusSeconds(3600);

        assertThatThrownBy(() -> new ReportWindow(start, end))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be after");
    }

    @Test
    void shouldThrowWhenStartNull() {
        Instant end = Instant.now();

        assertThatThrownBy(() -> new ReportWindow(null, end)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowWhenEndNull() {
        Instant start = Instant.now();

        assertThatThrownBy(() -> new ReportWindow(start, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCreateFromLocal() {
        LocalDateTime start = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 1, 1, 0);

        ReportWindow window = ReportWindow.fromLocal(start.atZone(ZoneOffset.UTC), end.atZone(ZoneOffset.UTC));

        assertThat(window.windowStartUtc()).isEqualTo(start.toInstant(ZoneOffset.UTC));
        assertThat(window.windowEndUtc()).isEqualTo(end.toInstant(ZoneOffset.UTC));
    }

    @Test
    void shouldCalculateDuration() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-01-01T01:30:00Z");

        ReportWindow window = new ReportWindow(start, end);

        assertThat(window.duration()).isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void shouldCheckContains() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-01-01T01:00:00Z");

        ReportWindow window = new ReportWindow(start, end);

        assertThat(window.contains(Instant.parse("2024-01-01T00:30:00Z"))).isTrue();
        assertThat(window.contains(start)).isTrue();
        assertThat(window.contains(end)).isFalse(); // exclusive of end
        assertThat(window.contains(Instant.parse("2023-12-31T23:59:59Z"))).isFalse();
        assertThat(window.contains(Instant.parse("2024-01-01T01:00:01Z"))).isFalse();
    }
}
