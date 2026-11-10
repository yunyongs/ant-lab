package antlab.support;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate 가 DB 로 보내는 SQL 을 스레드별로 기록한다(시험 프로필에서 {@code statement_inspector} 로 등록).
 * {@link #start()} 를 부른 스레드만 기록하고, {@link #stop()} 이 그때까지의 SQL 을 돌려준다.
 */
public class SqlRecorder implements StatementInspector {

    private static final ThreadLocal<List<String>> CURRENT = new ThreadLocal<>();

    public static void start() {
        CURRENT.set(new ArrayList<>());
    }

    public static List<String> stop() {
        List<String> recorded = CURRENT.get();
        CURRENT.remove();
        return recorded == null ? List.of() : recorded;
    }

    @Override
    public String inspect(String sql) {
        List<String> recorded = CURRENT.get();
        if (recorded != null) {
            recorded.add(sql);
        }
        return sql;
    }
}
