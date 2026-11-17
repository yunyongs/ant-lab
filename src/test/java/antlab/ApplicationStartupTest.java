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
    void 문맥은_공유_컨테이너_안의_전용_데이터베이스를_쓴다() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isNotEqualTo(postgres.getDatabaseName());
    }

    @Test
    void 시험_DB는_주입한_Testcontainers_컨테이너다() throws Exception {
        try (var conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            // the context gets its own database inside the shared container, so compare host and mapped port, not the database name
            String server = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/";
            assertThat(url).startsWith(server).doesNotContain(":5432/");
        }
    }
}
