package com.example.commander.repository.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.commander.domain.config.RecipientRow;
import com.example.commander.domain.config.ReportConfigRow;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link JdbcReportConfigLookupRepository}.
 *
 * <p>Its single-row lookup contract (empty / exactly-one / more-than-one) is worth locking
 * down with a fully mockable {@link JdbcTemplate} — unlike the TVP-backed staged queries in
 * {@link JdbcConfigurationReadRepository}, this class talks to {@code JdbcTemplate}
 * directly with no SQL Server-specific binding, so it doesn't need a real database to
 * unit test.
 */
@ExtendWith(MockitoExtension.class)
class JdbcReportConfigLookupRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcReportConfigLookupRepository repository;

    @Test
    void findRecipientByTypeAndValueReturnsEmptyWhenNoMatch() {
        repository = new JdbcReportConfigLookupRepository(jdbcTemplate);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("BIC"), eq("SOMEBIC")))
                .thenReturn(List.of());

        Optional<RecipientRow> result = repository.findRecipientByTypeAndValue("BIC", "SOMEBIC");

        assertThat(result).isEmpty();
    }

    @Test
    void findRecipientByTypeAndValueReturnsSingleMatch() {
        repository = new JdbcReportConfigLookupRepository(jdbcTemplate);
        RecipientRow recipient = new RecipientRow(999L, "BIC", "SOMEBIC", "Some Recipient");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("BIC"), eq("SOMEBIC")))
                .thenReturn(List.of(recipient));

        Optional<RecipientRow> result = repository.findRecipientByTypeAndValue("BIC", "SOMEBIC");

        assertThat(result).contains(recipient);
    }

    @Test
    void findRecipientByTypeAndValueThrowsWhenMultipleRowsMatch() {
        repository = new JdbcReportConfigLookupRepository(jdbcTemplate);
        RecipientRow recipient1 = new RecipientRow(1L, "BIC", "SOMEBIC", "First");
        RecipientRow recipient2 = new RecipientRow(2L, "BIC", "SOMEBIC", "Second");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("BIC"), eq("SOMEBIC")))
                .thenReturn(List.of(recipient1, recipient2));

        assertThatThrownBy(() -> repository.findRecipientByTypeAndValue("BIC", "SOMEBIC"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2");
    }

    @Test
    void findRecipientByIdReturnsEmptyWhenNoMatch() {
        repository = new JdbcReportConfigLookupRepository(jdbcTemplate);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(999L))).thenReturn(List.of());

        Optional<RecipientRow> result = repository.findRecipientById(999L);

        assertThat(result).isEmpty();
    }

    @Test
    void findRecipientByIdReturnsSingleMatch() {
        repository = new JdbcReportConfigLookupRepository(jdbcTemplate);
        RecipientRow recipient = new RecipientRow(999L, "BIC", "SOMEBIC", "Some Recipient");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(999L))).thenReturn(List.of(recipient));

        Optional<RecipientRow> result = repository.findRecipientById(999L);

        assertThat(result).contains(recipient);
    }

    @Test
    void findByRecipientAndReportTypeReturnsEmptyWhenNoMatch() {
        repository = new JdbcReportConfigLookupRepository(jdbcTemplate);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(999L), eq("CAMT054C")))
                .thenReturn(List.of());

        Optional<ReportConfigRow> result = repository.findByRecipientAndReportType(999L, "CAMT054C");

        assertThat(result).isEmpty();
    }

    @Test
    void findByRecipientAndReportTypeReturnsSingleMatchRelyingOnUniqueConstraint() {
        repository = new JdbcReportConfigLookupRepository(jdbcTemplate);
        ReportConfigRow config = new ReportConfigRow(
                1L, 12345678, "CAMT054C", "1.0", "ONE_TIME_PER_DAY", "desc", 999L, "IBAN", true, false, false, true);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(999L), eq("CAMT054C")))
                .thenReturn(List.of(config));

        Optional<ReportConfigRow> result = repository.findByRecipientAndReportType(999L, "CAMT054C");

        assertThat(result).contains(config);
    }

    @Test
    void findByRecipientAndReportTypeThrowsWhenMultipleRowsMatch() {
        repository = new JdbcReportConfigLookupRepository(jdbcTemplate);
        ReportConfigRow config1 = new ReportConfigRow(
                1L, 11111111, "CAMT054C", "1.0", "ONE_TIME_PER_DAY", "desc", 999L, "IBAN", true, false, false, true);
        ReportConfigRow config2 = new ReportConfigRow(
                2L, 22222222, "CAMT054C", "1.0", "ONE_TIME_PER_DAY", "desc", 999L, "IBAN", true, false, false, true);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(999L), eq("CAMT054C")))
                .thenReturn(List.of(config1, config2));

        assertThatThrownBy(() -> repository.findByRecipientAndReportType(999L, "CAMT054C"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique-constraint");
    }
}
