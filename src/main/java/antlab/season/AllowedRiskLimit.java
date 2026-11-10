package antlab.season;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.util.List;

/** 위험 선언은 −10·−20·−30·−40% 중 하나(PLAN §4.1-1). 값 비교라 {@code -0.2} 와 {@code -0.20} 은 같다. */
@Documented
@Constraint(validatedBy = AllowedRiskLimit.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedRiskLimit {

    String message() default "허용된 위험 선언이 아니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** 허용 목록(상수 — 설정이 아니다). */
    List<BigDecimal> ALLOWED = List.of(
            new BigDecimal("-0.10"), new BigDecimal("-0.20"), new BigDecimal("-0.30"), new BigDecimal("-0.40"));

    class Validator implements ConstraintValidator<AllowedRiskLimit, BigDecimal> {
        @Override
        public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
            // null 은 @NotNull 이 따로 거절한다
            return value == null || ALLOWED.stream().anyMatch(a -> a.compareTo(value) == 0);
        }
    }
}
