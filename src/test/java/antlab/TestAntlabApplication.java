package antlab;

import antlab.support.PostgresTestcontainersConfig;
import org.springframework.boot.SpringApplication;

/** {@code ./gradlew bootTestRun}: 일회용 PostgreSQL 17 로 앱을 띄운다(소유자 DB 불필요). */
public class TestAntlabApplication {

    public static void main(String[] args) {
        SpringApplication.from(AntlabApplication::main)
                .with(PostgresTestcontainersConfig.class)
                .run(args);
    }
}
