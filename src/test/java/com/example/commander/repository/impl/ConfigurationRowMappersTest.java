package com.example.commander.repository.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AgreementScopeRow;
import com.example.commander.domain.config.AliasAssignmentRow;
import com.example.commander.domain.config.PaymentTypeAssignmentRow;
import com.example.commander.domain.config.RecipientRow;
import com.example.commander.domain.config.ReportConfigRow;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ConfigurationRowMappers}.
 *
 * <p>Verifies each {@code RowMapper} projects the expected result-set columns (per the
 * SQL aliases used in {@link JdbcConfigurationReadRepository} and
 * {@link JdbcReportConfigLookupRepository}) onto the correct record fields, independent
 * of any real database.
 */
@ExtendWith(MockitoExtension.class)
class ConfigurationRowMappersTest {

    @Mock
    private ResultSet rs;

    @Test
    void reportConfigMapperProjectsAllColumns() throws Exception {
        when(rs.getLong("Id")).thenReturn(1L);
        when(rs.getInt("ConfigId")).thenReturn(12345678);
        when(rs.getString("ReportType")).thenReturn("CAMT054C");
        when(rs.getString("ReportVersion")).thenReturn("1.0");
        when(rs.getString("ReportFrequency")).thenReturn("ONE_TIME_PER_DAY");
        when(rs.getString("Description")).thenReturn("desc");
        when(rs.getLong("MessageRecipientId")).thenReturn(999L);
        when(rs.getString("AccountFormat")).thenReturn("IBAN");
        when(rs.getBoolean("IsActive")).thenReturn(true);
        when(rs.getBoolean("IsPaginated")).thenReturn(false);
        when(rs.getBoolean("IsEmptyReportAllowed")).thenReturn(true);
        when(rs.getBoolean("IsBundled")).thenReturn(false);

        ReportConfigRow row = ConfigurationRowMappers.REPORT_CONFIG.mapRow(rs, 0);

        assertThat(row)
                .isEqualTo(new ReportConfigRow(
                        1L,
                        12345678,
                        "CAMT054C",
                        "1.0",
                        "ONE_TIME_PER_DAY",
                        "desc",
                        999L,
                        "IBAN",
                        true,
                        false,
                        true,
                        false));
    }

    @Test
    void agreementScopeMapperProjectsAliasedColumns() throws Exception {
        when(rs.getLong("scopeId")).thenReturn(101L);
        when(rs.getLong("reportConfigId")).thenReturn(1L);
        when(rs.getString("scopeName")).thenReturn("Scope A");

        AgreementScopeRow row = ConfigurationRowMappers.AGREEMENT_SCOPE.mapRow(rs, 0);

        assertThat(row).isEqualTo(new AgreementScopeRow(101L, 1L, "Scope A"));
    }

    @Test
    void paymentTypeAssignmentMapperProjectsAliasedColumns() throws Exception {
        when(rs.getLong("assignmentId")).thenReturn(201L);
        when(rs.getLong("scopeId")).thenReturn(101L);
        when(rs.getString("paymentType")).thenReturn("SWISH");

        PaymentTypeAssignmentRow row = ConfigurationRowMappers.PAYMENT_TYPE_ASSIGNMENT.mapRow(rs, 0);

        assertThat(row).isEqualTo(new PaymentTypeAssignmentRow(201L, 101L, "SWISH"));
    }

    @Test
    void accountAssignmentMapperProjectsAliasedColumns() throws Exception {
        when(rs.getLong("accountId")).thenReturn(301L);
        when(rs.getLong("assignmentId")).thenReturn(201L);
        when(rs.getString("clearingNumber")).thenReturn("1234");
        when(rs.getString("accountNumber")).thenReturn("5678901");
        when(rs.getString("accountBban")).thenReturn(null);
        when(rs.getString("currency")).thenReturn("SEK");

        AccountAssignmentRow row = ConfigurationRowMappers.ACCOUNT_ASSIGNMENT.mapRow(rs, 0);

        assertThat(row).isEqualTo(new AccountAssignmentRow(301L, 201L, "1234", "5678901", null, "SEK"));
    }

    @Test
    void aliasAssignmentMapperProjectsAliasedColumns() throws Exception {
        when(rs.getLong("aliasRowId")).thenReturn(401L);
        when(rs.getLong("assignmentId")).thenReturn(201L);
        when(rs.getString("aliasId")).thenReturn("ALIAS-1");

        AliasAssignmentRow row = ConfigurationRowMappers.ALIAS_ASSIGNMENT.mapRow(rs, 0);

        assertThat(row).isEqualTo(new AliasAssignmentRow(401L, 201L, "ALIAS-1"));
    }

    @Test
    void recipientMapperProjectsAllColumns() throws Exception {
        when(rs.getLong("Id")).thenReturn(999L);
        when(rs.getString("Type")).thenReturn("BIC");
        when(rs.getString("Value")).thenReturn("SOMEBIC");
        when(rs.getString("Name")).thenReturn("Some Recipient");

        RecipientRow row = ConfigurationRowMappers.RECIPIENT.mapRow(rs, 0);

        assertThat(row).isEqualTo(new RecipientRow(999L, "BIC", "SOMEBIC", "Some Recipient"));
    }
}
