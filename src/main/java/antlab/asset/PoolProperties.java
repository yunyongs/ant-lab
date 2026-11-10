package antlab.asset;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 종목 풀 설정. 기준일은 서버가 정한다(사용자가 고르지 않는다).
 * 시즌 시작(P0-30)도 같은 값을 {@code season.base_date} 로 쓴다 — 풀과 시즌 기준일이 갈라지지 않게.
 */
@Validated
@ConfigurationProperties("antlab.pool")
public record PoolProperties(@NotNull LocalDate baseDate) {
}
