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

/** 포메이션 네 칸의 합이 100 이어야 한다. 칸이 비어 있으면 {@code @NotNull} 이 따로 거절하므로 여기서는 지나간다. */
@Documented
@Constraint(validatedBy = FormationSum.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FormationSum {

    String message() default "포메이션 비중의 합은 100 이어야 한다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<FormationSum, FormationDto> {
        @Override
        public boolean isValid(FormationDto value, ConstraintValidatorContext context) {
            if (value == null || value.goalkeeper() == null || value.defender() == null
                    || value.midfielder() == null || value.forward() == null) {
                return true;
            }
            long sum = (long) value.goalkeeper() + value.defender() + value.midfielder() + value.forward();
            return sum == 100;
        }
    }
}
