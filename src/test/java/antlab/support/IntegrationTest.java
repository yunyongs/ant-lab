package antlab.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** 앱 전체 + MockMvc + JVM 당 하나인 PostgreSQL 17(문맥마다 전용 데이터베이스). 같은 구성의 시험끼리 문맥을 공유한다. Every request carries a default bearer token ({@link DefaultBearerConfig}). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestcontainersConfig.class, ContractErrorCheck.class, TestTokens.class, TestMembers.class,
        DefaultBearerConfig.class, JdbcClientRecording.class})
public @interface IntegrationTest {
}
