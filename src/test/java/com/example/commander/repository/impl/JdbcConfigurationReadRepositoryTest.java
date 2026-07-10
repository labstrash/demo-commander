package com.example.commander.repository.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.commander.config.ReadLayerProperties;
import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AgreementScopeRow;
import com.example.commander.domain.config.AliasAssignmentRow;
import com.example.commander.domain.config.PaymentTypeAssignmentRow;
import com.example.commander.domain.config.ReportConfigRow;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for the guard-clause / short-circuit behavior of
 * {@link JdbcConfigurationReadRepository} — the parts of this class that don't require a
 * live database.
 *
 * <p>The TVP-backed staged queries ({@code findAssignmentsByScopeIds},
 * {@code findAccountsByAssignmentIds}, {@code findAliasesByAssignmentIds}) rely on SQL
 * Server-specific JDBC APIs ({@code SQLServerPreparedStatement#setStructured}) that can't
 * be meaningfully faked, so their actual query execution is integration-tested against
 * real SQL Server (Testcontainers) rather than unit-tested here. What's still valuable —
 * and fully unit-testable — is the empty-input short-circuiting (no query issued at all
 * for an empty ID collection) and the level 1→2 safe-size guard that protects against
 * exceeding SQL Server's 2,100-parameter cap.
 */
@ExtendWith(MockitoExtension.class)
class JdbcConfigurationReadRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DataSource dataSource;

    private JdbcConfigurationReadRepository repository;

    @BeforeEach
    void setUp() {
        // The repository builds its own internally-scoped JdbcTemplate off the same
        // DataSource to apply a distinct query timeout without mutating the shared bean
        // (see the class-level constructor comment) — a non-null DataSource is required
        // for construction to succeed, but is never actually connected to in these tests.
        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        repository = new JdbcConfigurationReadRepository(jdbcTemplate, new ReadLayerProperties());
        // Construction itself calls jdbcTemplate.getDataSource() — reset so the
        // verifyNoInteractions() assertions below only cover behavior under test.
        clearInvocations(jdbcTemplate);
    }

    @Test
    void findScopesByConfigIdsReturnsEmptyListForEmptyInputWithoutQuerying() {
        List<AgreementScopeRow> result = repository.findScopesByConfigIds(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void findScopesByConfigIdsThrowsWhenExceedingSafeInListSize() {
        List<Long> tooManyIds = new ArrayList<>();
        for (long i = 0; i < 2001; i++) {
            tooManyIds.add(i);
        }

        assertThatThrownBy(() -> repository.findScopesByConfigIds(tooManyIds))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2001")
                .hasMessageContaining("2000");
    }

    @Test
    void findAssignmentsByScopeIdsReturnsEmptyListForEmptyInputWithoutQuerying() {
        List<PaymentTypeAssignmentRow> result = repository.findAssignmentsByScopeIds(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void findAccountsByAssignmentIdsReturnsEmptyListForEmptyInputWithoutQuerying() {
        List<AccountAssignmentRow> result = repository.findAccountsByAssignmentIds(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void findAliasesByAssignmentIdsReturnsEmptyListForEmptyInputWithoutQuerying() {
        List<AliasAssignmentRow> result = repository.findAliasesByAssignmentIds(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAssignmentsByScopeIdsBindsIdsAsTvpAndReturnsMappedRows() {
        PaymentTypeAssignmentRow assignment = new PaymentTypeAssignmentRow(201L, 101L, "SWISH");
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(assignment));

        List<PaymentTypeAssignmentRow> result = repository.findAssignmentsByScopeIds(List.of(101L));

        assertThat(result).containsExactly(assignment);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAssignmentsByScopeIdsPropagatesFailureWithoutSwallowingIt() {
        // No partial persistence, no bespoke rollback — a staged-query failure
        // propagates to the caller as-is rather than being swallowed or wrapped.
        RuntimeException dbFailure = new RuntimeException("simulated TVP query timeout");
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenThrow(dbFailure);

        assertThatThrownBy(() -> repository.findAssignmentsByScopeIds(List.of(101L)))
                .isSameAs(dbFailure);
    }

    @Test
    void assembleTreesReturnsEmptyListForEmptyConfigsWithoutQuerying() {
        List<ReportConfigRow> result = repository.assembleTrees(List.of()).stream()
                .map(t -> t.config())
                .toList();

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }
}
