package com.example.commander.domain.config;

import java.util.List;

/**
 * The fully assembled report configuration hierarchy for a single report.
 *
 * <p>Contains the report configuration header along with all associated scopes,
 * payment type assignments, and account or alias details. This is the complete
 * structure that both scheduled and on-demand report generation paths use
 * before fan-out and bundling.
 *
 * <p>Zero scopes is a valid business case, resulting in a configuration-only message.
 *
 * @param config the report configuration header
 * @param scopes the list of agreement scopes linked to this configuration
 */
public record ReportConfigTree(ReportConfigRow config, List<AgreementScopeNode> scopes) {

    public ReportConfigTree {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /**
     * Returns true if this configuration has no linked scopes.
     *
     * <p>A configuration with zero scopes is a valid business case that produces
     * a configuration-only message downstream.
     *
     * @return true if the scopes list is empty
     */
    public boolean isZeroScope() {
        return scopes.isEmpty();
    }
}
