package com.example.commander.domain.message;

import io.hypersistence.tsid.TSID;

public class ReportMessageIdGenerator {

    /**
     * Generates a unique identifier for a report message.
     *
     * Constraints:
     * - Must be unique.
     * - Must be less than 35 characters in total length.
     *
     * Format: "FIKASE" + shortType + reportId + tsid + paginationNumber
     *
     * Example: FIKASE752BT0Q9Z6XZHPAH5R0000
     *
     * @param reportId The ID of the report (unique per report).
     * @param reportType The type of the report (must be at least 6 characters long).
     * @return A unique identifier string for the report message.
     * @throws IllegalArgumentException if the reportType is null or shorter than 6 characters.
     */
    public static String generateReportMessageId(long reportId, String reportType) {

        // Validate that the provided report type is valid
        if (reportType == null || reportType.length() < 6) {
            throw new IllegalArgumentException("Report type must have at least 6 characters.");
        }

        // Extract a compact representation of the report type
        String shortType = reportType.replace("-", "").toUpperCase().substring(5);

        // Generate a globally unique TSID value
        String tsid = TSID.Factory.builder().build().generate().toString();

        // Placeholder for pagination (future requirement)
        String pageNumber = "0000";

        // Construct and return the final message ID
        return "FIKASE" + shortType + reportId + tsid + pageNumber;
    }
}
