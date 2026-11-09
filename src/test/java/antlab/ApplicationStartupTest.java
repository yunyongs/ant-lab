package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.postgresql.PostgreSQLContainer;

@IntegrationTest
class ApplicationStartupTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ApplicationContext context;

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void 앱_문맥이_PostgreSQL_17에_연결되어_뜬다() {
        Integer version = jdbc.queryForObject("SHOW server_version_num", Integer.class);
        assertThat(version).isGreaterThanOrEqualTo(170000).isLessThan(180000);
    }

    @Test
    void 시험_전용_컨트롤러가_다른_시험_문맥으로_새지_않는다() {
        // ProblemDetailTest·JsonMoneyFormatTest 의 중첩 컨트롤러는 @Import 한 문맥에만 있어야 한다
        assertThat(context.getBeansWithAnnotation(RestController.class).values())
                .noneMatch(bean -> bean.getClass().getName().contains("Probe"));
    }

    @Test
    void 시험_DB는_주입한_Testcontainers_컨테이너다() throws Exception {
        try (var conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            assertThat(url).isEqualTo(postgres.getJdbcUrl()).doesNotContain(":5432/");
        }
    }
}
