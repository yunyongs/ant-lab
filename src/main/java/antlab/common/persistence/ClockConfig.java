package antlab.common.persistence;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시각의 출처 한 곳. 감사 컬럼(created_at·updated_at)이 이 시계를 쓴다(SPRING_JPA §4.5). */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
