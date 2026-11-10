package antlab.season;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 시즌 시작이 새 시즌에 적는 설정(요청에서 받지 않는다). 기준일은 {@code antlab.pool.base-date} 를 그대로 쓴다.
 * 값이 빠지거나 DB 제약 밖이면 기동에 실패한다 — 시즌 행의 8개 설정 컬럼에 기본값이 없기 때문이다.
 */
@Validated
@ConfigurationProperties("antlab.season")
public record SeasonProperties(
        @NotNull @Positive @Digits(integer = 15, fraction = 0) BigDecimal initialCash,
        @NotNull @DecimalMin("0") @DecimalMax(value = "1", inclusive = false) @Digits(integer = 1, fraction = 8)
        BigDecimal feeRateKr,
        @NotNull @DecimalMin("0") @DecimalMax(value = "1", inclusive = false) @Digits(integer = 1, fraction = 8)
        BigDecimal feeRateUs,
        @NotNull @DecimalMin("0") @DecimalMax(value = "1", inclusive = false) @Digits(integer = 1, fraction = 8)
        BigDecimal fxSpreadRate,
        @NotNull LocalDate dataSnapshotDate) {
}
