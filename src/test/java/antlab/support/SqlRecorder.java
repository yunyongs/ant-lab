package antlab.support;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Records the SQL Hibernate sends, per thread (registered as {@code statement_inspector} in the test profile).
 * Only the thread that called {@link #start()} records; {@link #stop()} returns its Hibernate statements.
 * JdbcClient statements arrive from {@link JdbcClientRecording} through {@link #recordJdbcClient(String)},
 * and {@link #stopAll()} returns both kinds separately. Per-kind statement-count assertions use these values.
 */
public class SqlRecorder implements StatementInspector {

    /** Statements seen between {@link #start()} and {@link #stopAll()}, split by the path that sent them. */
    public record Recorded(List<String> hibernate, List<String> jdbcClient) {
        public int total() {
            return hibernate.size() + jdbcClient.size();
        }

        public List<String> all() {
            List<String> out = new ArrayList<>(hibernate);
            out.addAll(jdbcClient);
            return out;
        }
    }

    private static final ThreadLocal<Recorded> CURRENT = new ThreadLocal<>();

    public static void start() {
        CURRENT.set(new Recorded(new ArrayList<>(), new ArrayList<>()));
    }

    /** Hibernate statements only (the original contract; JdbcClient statements are not part of this list). */
    public static List<String> stop() {
        return stopAll().hibernate();
    }

    public static Recorded stopAll() {
        Recorded recorded = CURRENT.get();
        CURRENT.remove();
        return recorded == null ? new Recorded(List.of(), List.of()) : recorded;
    }

    static void recordJdbcClient(String sql) {
        Recorded recorded = CURRENT.get();
        if (recorded != null) {
            recorded.jdbcClient().add(sql);
        }
    }

    @Override
    public String inspect(String sql) {
        Recorded recorded = CURRENT.get();
        if (recorded != null) {
            recorded.hibernate().add(sql);
        }
        return sql;
    }
}
