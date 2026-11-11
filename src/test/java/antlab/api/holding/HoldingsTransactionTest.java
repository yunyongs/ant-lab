package antlab.api.holding;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.HoldingsQueryService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 보유 조회는 읽기 전용 + REPEATABLE_READ 다 — 계좌·보유·라인업 여러 문장이 한 스냅샷을 보게 한다(리플렉션으로 선언을 고정한다). */
class HoldingsTransactionTest {

    @Test
    void 보유_조회_서비스_메서드는_읽기_전용이고_반복_가능한_읽기_격리다() throws Exception {
        Method get = HoldingsQueryService.class.getMethod("get", Long.class);
        Transactional tx = get.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }
}
