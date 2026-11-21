package antlab.support;

import antlab.support.RowSet.ColumnType;
import antlab.support.RowSet.RowQuery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Read access to a database that already holds the full history (db/30 load, then db/12 and db/13), for the full-data golden
 * tier. Configured by {@value #ENV_URL} (JDBC URL), {@value #ENV_USER} and {@value #ENV_PASSWORD}; without the URL the tier
 * skips. The tests only read, but the position check loads {@code db/12_calc_position_def.sql}, which creates temporary views and
 * functions in the session, so the role needs SELECT and TEMP (and no write rights on the data). Values never reach messages.
 */
public final class FullDataDb {

    public static final String ENV_URL = "ANTLAB_FULL_DATA_DB_URL";
    public static final String ENV_USER = "ANTLAB_FULL_DATA_DB_USER";
    public static final String ENV_PASSWORD = "ANTLAB_FULL_DATA_DB_PASSWORD";

    public static final String SKIP_REASON = "full-data golden skipped: set " + ENV_URL + " (and " + ENV_USER + ", "
            + ENV_PASSWORD + ") to a database loaded by db/30 and calculated by db/12 and db/13; CI never sets it";

    private final String url;
    private final String user;
    private final String password;

    public FullDataDb(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Null when the URL is not set (the tier skips). */
    public static FullDataDb fromEnvironment() {
        String url = System.getenv(ENV_URL);
        if (url == null || url.isBlank()) {
            return null;
        }
        return new FullDataDb(url, System.getenv(ENV_USER), System.getenv(ENV_PASSWORD));
    }

    public RowSet read(RowQuery query) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setReadOnly(true);
            return query(c, query);
        }
    }

    /** Runs a SQL definition file (temporary views and functions, plain SQL) in the same session, then the query. */
    public RowSet readWithDefinition(Path definition, RowQuery query) throws Exception {
        String sql = Files.readString(definition);
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
            return query(c, query);
        }
    }

    private static RowSet query(Connection c, RowQuery query) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(query.sql().replace(":sid", "0"))) {
            ResultSetMetaData meta = rs.getMetaData();
            if (meta.getColumnCount() != query.columns().size()) {
                throw new IllegalStateException(query.name() + ": column count differs from the declaration");
            }
            for (int i = 0; i < query.columns().size(); i++) {
                if (!meta.getColumnLabel(i + 1).equals(query.columns().get(i).name())) {
                    throw new IllegalStateException(query.name() + ": column " + (i + 1) + " name differs from the declaration");
                }
                SqlGoldenHarness.requireColumnType(query, i, meta.getColumnTypeName(i + 1));
            }
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 0; i < query.columns().size(); i++) {
                    ColumnType type = query.columns().get(i).type();
                    row.add(SqlGoldenHarness.readCell(rs, i + 1, type));
                }
                rows.add(row);
            }
            return new RowSet(query.columns(), rows);
        }
    }
}
