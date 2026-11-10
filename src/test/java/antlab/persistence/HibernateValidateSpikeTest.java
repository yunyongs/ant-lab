package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.IntegrationTest;
import java.util.Properties;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import probe.SmallIntAnnotatedProbe;
import probe.SmallIntProbe;

/**
 * 회귀 시험: SMALLINT 열은 {@code @JdbcTypeCode(SMALLINT)} 없이 {@code int} 로 매핑하면 Hibernate {@code validate} 가 거부한다
 * (SPRING_JPA §4.1 규칙의 근거). Hibernate 를 올릴 때 이 가정이 바뀌면 이 시험이 먼저 알려 준다.
 * 별도 표(spike_smallint)와 별도 SessionFactory 를 쓰고 끝나면 지운다.
 */
@IntegrationTest
class HibernateValidateSpikeTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PostgreSQLContainer postgres;

    @BeforeEach
    void 표_만들기() {
        jdbc.execute("CREATE TABLE spike_smallint (id BIGINT PRIMARY KEY, n SMALLINT NOT NULL)");
    }

    @AfterEach
    void 표_지우기() {
        jdbc.execute("DROP TABLE IF EXISTS spike_smallint");
    }

    private SessionFactory validating(Class<?> entity) {
        Properties props = new Properties();
        props.put("hibernate.connection.url", postgres.getJdbcUrl());
        props.put("hibernate.connection.username", postgres.getUsername());
        props.put("hibernate.connection.password", postgres.getPassword());
        props.put("hibernate.hbm2ddl.auto", "validate");
        return new Configuration().addProperties(props).addAnnotatedClass(entity).buildSessionFactory();
    }

    @Test
    void int에_SMALLINT_명시가_없으면_validate가_시작을_거부한다() {
        assertThatThrownBy(() -> validating(SmallIntProbe.class).close())
                .isInstanceOf(Exception.class)
                .satisfies(e -> {
                    Throwable root = e;
                    boolean schema = false;
                    for (Throwable c = e; c != null; c = c.getCause()) {
                        schema |= c instanceof SchemaManagementException;
                        root = c;
                    }
                    assertThat(schema).as("SchemaManagementException 이 원인 사슬에 있다: " + root).isTrue();
                });
    }

    @Test
    void SMALLINT_명시가_있으면_validate가_통과한다() {
        assertThatCode(() -> validating(SmallIntAnnotatedProbe.class).close()).doesNotThrowAnyException();
    }
}
