package com.example.commander.repository.tvp;

import com.microsoft.sqlserver.jdbc.SQLServerDataTable;
import com.microsoft.sqlserver.jdbc.SQLServerException;
import com.microsoft.sqlserver.jdbc.SQLServerPreparedStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;

/**
 * Binds a collection of IDs as a SQL Server Table-Valued Parameter (TVP).
 *
 * <p>TVPs bypass the 2,100-parameter limit of SQL Server's {@code IN (...)} clause,
 * allowing large collections of IDs to be passed to stored procedures or queries.
 *
 * <p>This helper requires the SQL Server JDBC driver and the {@code dbo.BigIntIdList}
 * user-defined table type to exist in the database.
 *
 * <p>Methods using this helper should be integration-tested against a real SQL Server
 * (e.g., Testcontainers) as the driver-specific API cannot be easily mocked.
 */
public final class TvpParameterSource {

    /**
     * The SQL Server user-defined table type name for BIGINT ID lists. Must match the
     * {@code CREATE TYPE} in the schema migration that provisions {@code dbo.BigIntIdList} —
     * renaming this constant without updating that migration will break TVP binding at
     * runtime, not at compile time.
     */
    public static final String BIGINT_ID_LIST_TYPE = "dbo.BigIntIdList";

    private static final String COLUMN_NAME = "Id";

    private TvpParameterSource() {}

    /**
     * Binds a collection of IDs as a TVP at the specified parameter index.
     *
     * @param ps the prepared statement
     * @param parameterIndex the 1-based parameter index
     * @param ids the collection of IDs to bind (must not be null or empty)
     * @throws IllegalArgumentException if ids is null or empty
     * @throws SQLException if the TVP cannot be built or bound
     */
    public static void bindIds(PreparedStatement ps, int parameterIndex, Collection<Long> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids must not be null or empty when binding a TVP parameter");
        }

        SQLServerDataTable table;
        try {
            table = new SQLServerDataTable();
            table.addColumnMetadata(COLUMN_NAME, Types.BIGINT);
            for (Long id : ids) {
                table.addRow(id);
            }
        } catch (SQLServerException e) {
            throw new SQLException("Failed to build TVP payload for BigIntIdList", e);
        }

        SQLServerPreparedStatement sqlServerStatement = ps.unwrap(SQLServerPreparedStatement.class);
        sqlServerStatement.setStructured(parameterIndex, BIGINT_ID_LIST_TYPE, table);
    }
}
