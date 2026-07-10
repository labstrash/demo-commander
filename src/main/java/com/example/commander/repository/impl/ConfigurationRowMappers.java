package com.example.commander.repository.impl;

import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AgreementScopeRow;
import com.example.commander.domain.config.AliasAssignmentRow;
import com.example.commander.domain.config.PaymentTypeAssignmentRow;
import com.example.commander.domain.config.RecipientRow;
import com.example.commander.domain.config.ReportConfigRow;
import org.springframework.jdbc.core.RowMapper;

/**
 * Shared {@link RowMapper} constants for configuration data.
 *
 * <p>Centralizes row mapping logic used by both
 * {@link JdbcConfigurationReadRepository} and other JDBC repositories,
 * ensuring consistent projection-to-object mapping across the codebase.
 */
public final class ConfigurationRowMappers {

    private ConfigurationRowMappers() {}

    public static final RowMapper<ReportConfigRow> REPORT_CONFIG = (rs, rowNum) -> new ReportConfigRow(
            rs.getLong("Id"),
            rs.getInt("ConfigId"),
            rs.getString("ReportType"),
            rs.getString("ReportVersion"),
            rs.getString("ReportFrequency"),
            rs.getString("Description"),
            rs.getLong("MessageRecipientId"),
            rs.getString("AccountFormat"),
            rs.getBoolean("IsActive"),
            rs.getBoolean("IsPaginated"),
            rs.getBoolean("IsEmptyReportAllowed"),
            rs.getBoolean("IsBundled"));

    public static final RowMapper<AgreementScopeRow> AGREEMENT_SCOPE = (rs, rowNum) ->
            new AgreementScopeRow(rs.getLong("scopeId"), rs.getLong("reportConfigId"), rs.getString("scopeName"));

    public static final RowMapper<PaymentTypeAssignmentRow> PAYMENT_TYPE_ASSIGNMENT =
            (rs, rowNum) -> new PaymentTypeAssignmentRow(
                    rs.getLong("assignmentId"), rs.getLong("scopeId"), rs.getString("paymentType"));

    public static final RowMapper<AccountAssignmentRow> ACCOUNT_ASSIGNMENT = (rs, rowNum) -> new AccountAssignmentRow(
            rs.getLong("accountId"),
            rs.getLong("assignmentId"),
            rs.getString("clearingNumber"),
            rs.getString("accountNumber"),
            rs.getString("accountBban"),
            rs.getString("currency"));

    public static final RowMapper<AliasAssignmentRow> ALIAS_ASSIGNMENT = (rs, rowNum) ->
            new AliasAssignmentRow(rs.getLong("aliasRowId"), rs.getLong("assignmentId"), rs.getString("aliasId"));

    public static final RowMapper<RecipientRow> RECIPIENT = (rs, rowNum) ->
            new RecipientRow(rs.getLong("Id"), rs.getString("Type"), rs.getString("Value"), rs.getString("Name"));
}
