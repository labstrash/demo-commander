package com.example.commander.domain.config;

/**
 * A scope associated with a report configuration.
 *
 * <p>Represents the eligibility scope for a report, indicating which agreements
 * or entities should be included when generating the report.
 *
 * <p>The presence of this scope row serves as the eligibility signal itself;
 * the status of the underlying agreement is not re-validated during report generation.
 *
 * @param id unique identifier for this scope association
 * @param reportConfigId ID of the parent report configuration
 * @param name human-readable name of the scope
 */
public record AgreementScopeRow(long id, long reportConfigId, String name) {}
