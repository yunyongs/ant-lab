package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 이후 모든 기능이 기대는 설정 값을 시험으로 고정한다. */
@IntegrationTest
class SettingsAssertionTest {

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void 영속성_설정() {
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(env.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("Asia/Seoul");
        assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
        assertThat(env.getProperty("spring.flyway.default-schema")).isEqualTo("public");
        assertThat(env.getProperty("spring.flyway.schemas")).isEqualTo("public");
        assertThat(env.getProperty("spring.flyway.validate-on-migrate")).isEqualTo("true");
        assertThat(env.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false");
        assertThat(env.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
    }

    @Test
    void JSON_입력_설정() {
        assertThat(env.getProperty("spring.jackson.deserialization.accept-float-as-int")).isEqualTo("false");
    }

    @Test
    void 액추에이터와_셧다운_설정() {
        assertThat(env.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
        assertThat(env.getProperty("management.endpoint.health.probes.add-additional-paths")).isEqualTo("true");
        assertThat(env.getProperty("management.metrics.tags.application")).isEqualTo("antlab-api");
        assertThat(env.getProperty("management.endpoint.health.group.readiness.include")).contains("db");
        assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");
        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
    }

    @Test
    void 시험_JVM은_UTC인데_DB_세션은_Asia_Seoul이다() {
        assertThat(java.util.TimeZone.getDefault().getID()).isEqualTo("UTC");
        assertThat(jdbc.queryForObject("SHOW TimeZone", String.class)).isEqualTo("Asia/Seoul");
    }

    @Test
    void 컨테이너_서버_기본_시간대는_Asia_Seoul이다() throws Exception {
        // 서버 자체의 기본값(postgresql.conf). psql 은 connection-init-sql 을 거치지 않지만 컨테이너 env 의 PGTZ 를
        // 물려받으므로 PGTZ 를 빼고 붙는다.
        var result = postgres.execInContainer("env", "-u", "PGTZ",
                "psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(), "-tAc", "SHOW timezone");
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout().trim()).isEqualTo("Asia/Seoul");
    }
}
