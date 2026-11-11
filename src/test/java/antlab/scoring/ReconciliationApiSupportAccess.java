package antlab.scoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** {@link ReconciliationApiSupport} 를 다른 패키지의 시험이 쓰도록 여는 얇은 통로. */
public final class ReconciliationApiSupportAccess {

    private ReconciliationApiSupportAccess() {
    }

    public static MockHttpServletResponse buy(MockMvcTester mvc, JdbcTemplate jdbc, long seasonId, String ticker, int quantity,
            String position) {
        return ReconciliationApiSupport.buy(mvc, jdbc, seasonId, ticker, quantity, position);
    }

    public static long seasonWithOrders(MockMvcTester mvc, JdbcTemplate jdbc) throws Exception {
        return ReconciliationApiSupport.seasonWithOrders(mvc, jdbc);
    }
}
