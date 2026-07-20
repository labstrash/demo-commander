package com.example.commander.repository.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commander.domain.audit.ReportCommandAuditEntry;
import com.example.commander.domain.audit.ReportCommandAuditStatus;
import com.example.commander.domain.message.TriggerType;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

/**
 * Unit tests for {@link JdbcReportCommandAuditRepository}, mocking {@link JdbcTemplate} — same
 * pattern as {@code JdbcDeadLetterMessageRepositoryTest}, no real database needed.
 */
@ExtendWith(MockitoExtension.class)
class JdbcReportCommandAuditRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PreparedStatement preparedStatement;

    private JdbcReportCommandAuditRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcReportCommandAuditRepository(jdbcTemplate);
    }

    @Test
    void existsSentReturnsTrueWhenQueryReturnsOne() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq("corr-id")))
                .thenReturn(true);

        assertThat(repository.existsSent("corr-id")).isTrue();
    }

    @Test
    void existsSentReturnsFalseWhenQueryReturnsFalseOrNull() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq("corr-id")))
                .thenReturn(false);
        assertThat(repository.existsSent("corr-id")).isFalse();
    }

    @Test
    void insertBindsEveryFieldInOrder() throws Exception {
        ReportCommandAuditEntry entry = entry(ReportCommandAuditStatus.SENT, "jms-msg-id", null, 1L, 2L, 0);

        ArgumentCaptor<PreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        repository.insert(entry);
        verify(jdbcTemplate).update(anyString(), setterCaptor.capture());
        setterCaptor.getValue().setValues(preparedStatement);

        verify(preparedStatement).setString(1, "msg-id");
        verify(preparedStatement).setString(2, "corr-id");
        verify(preparedStatement).setLong(3, 1L); // reportConfigId
        verify(preparedStatement).setString(4, "12345678"); // configId
        verify(preparedStatement).setLong(5, 2L); // agreementScopeId
        verify(preparedStatement).setString(6, "CAMT054C");
        verify(preparedStatement).setString(7, "1.0");
        verify(preparedStatement).setString(8, "DAILY");
        verify(preparedStatement).setString(9, "SCHEDULED");
        verify(preparedStatement).setBoolean(12, true); // isBundled
        verify(preparedStatement).setInt(13, 3); // accountCount
        verify(preparedStatement).setInt(14, 1); // paymentTypeCount
        verify(preparedStatement).setString(15, "BIC");
        verify(preparedStatement).setString(16, "SOMEBIC");
        verify(preparedStatement).setString(17, "CAMT.054C.QUEUE");
        verify(preparedStatement).setString(18, "jms-msg-id");
        verify(preparedStatement).setString(19, "SENT");
        verify(preparedStatement).setString(20, null); // errorMessage
        verify(preparedStatement).setInt(22, 0); // retryCount
        verify(preparedStatement).setLong(23, 1L); // jobExecutionId
        verify(preparedStatement).setLong(24, 2L); // stepExecutionId
        verify(preparedStatement).setString(25, "someone"); // requestorName
    }

    @Test
    void insertBindsNullForEveryNullableLongField() throws Exception {
        ReportCommandAuditEntry entry =
                entry(ReportCommandAuditStatus.FAILED, null, "TRANSIENT_EXHAUSTED: boom", null, null, 1);

        ArgumentCaptor<PreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        repository.insert(entry);
        verify(jdbcTemplate).update(anyString(), setterCaptor.capture());
        setterCaptor.getValue().setValues(preparedStatement);

        verify(preparedStatement).setNull(3, Types.BIGINT); // reportConfigId
        verify(preparedStatement).setNull(5, Types.BIGINT); // agreementScopeId
        verify(preparedStatement).setNull(23, Types.BIGINT); // jobExecutionId
        verify(preparedStatement).setNull(24, Types.BIGINT); // stepExecutionId
        verify(preparedStatement).setString(18, null); // mqMessageId
        verify(preparedStatement).setString(19, "FAILED");
        verify(preparedStatement).setString(20, "TRANSIENT_EXHAUSTED: boom");
    }

    @Test
    void deleteOlderThanReturnsAffectedRowCountAndFormatsTheLimitIntoTheSql() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.update(sqlCaptor.capture(), any(PreparedStatementSetter.class)))
                .thenReturn(42);

        int deleted = repository.deleteOlderThan(Instant.parse("2026-04-01T00:00:00Z"), 1000);

        assertThat(deleted).isEqualTo(42);
        // SQL Server's DELETE TOP doesn't accept a bind parameter for the row count - it has
        // to be formatted into the statement text, verified here rather than assumed.
        assertThat(sqlCaptor.getValue()).contains("TOP (1000)");
    }

    private static ReportCommandAuditEntry entry(
            ReportCommandAuditStatus status,
            String mqMessageId,
            String errorMessage,
            Long reportConfigId,
            Long agreementScopeId,
            int retryCount) {
        return ReportCommandAuditEntry.builder()
                .messageId("msg-id")
                .correlationId("corr-id")
                .reportConfigId(reportConfigId)
                .configId("12345678")
                .agreementScopeId(agreementScopeId)
                .reportType("CAMT054C")
                .reportVersion("1.0")
                .reportFrequency("DAILY")
                .triggerType(TriggerType.SCHEDULED)
                .windowStartUtc(Instant.parse("2026-07-01T00:00:00Z"))
                .windowEndUtc(Instant.parse("2026-07-02T00:00:00Z"))
                .isBundled(true)
                .accountCount(3)
                .paymentTypeCount(1)
                .recipientType("BIC")
                .recipientValue("SOMEBIC")
                .mqQueueName("CAMT.054C.QUEUE")
                .mqMessageId(mqMessageId)
                .status(status)
                .errorMessage(errorMessage)
                .sentAt(Instant.parse("2026-07-16T00:00:00Z"))
                .retryCount(retryCount)
                .jobExecutionId(reportConfigId == null ? null : 1L)
                .stepExecutionId(agreementScopeId == null ? null : 2L)
                .requestorName("someone")
                .build();
    }
}
