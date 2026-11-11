package antlab.api.formation;

import antlab.support.ScriptedClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 제어형 시계를 앱의 시계 대신 쓰는 시험 문맥 설정 — 이 설정을 가져온 시험만 별도 문맥을 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
class ScriptedClockConfig {

    @Bean
    @Primary
    ScriptedClock scriptedClock() {
        return new ScriptedClock();
    }
}
