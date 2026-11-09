package antlab.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 일회용 PostgreSQL 17 컨테이너. 시험과 {@code bootTestRun} 이 함께 쓴다.
 * 소유자의 상시 DB(127.0.0.1:5432)에는 접속하지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfig {

    /** 이미지는 이 한 곳에서만 정한다. */
    public static final String IMAGE = "postgres:17";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        // compose 의 db 와 같은 서버 시간대(Asia/Seoul)
        return new PostgreSQLContainer(IMAGE)
                .withEnv("TZ", "Asia/Seoul")
                .withEnv("PGTZ", "Asia/Seoul");
    }
}
