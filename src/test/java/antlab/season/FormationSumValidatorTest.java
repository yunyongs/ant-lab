package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 포메이션 검증: 합 100, 골키퍼 1 이상, 칸 범위 0–100, 빈 칸. 제약 조합을 실제 검증기로 본다. */
class FormationSumValidatorTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<ConstraintViolation<FormationDto>> check(Integer gk, Integer df, Integer mf, Integer fw) {
        return VALIDATOR.validate(new FormationDto(gk, df, mf, fw));
    }

    private static Set<String> names(Set<ConstraintViolation<FormationDto>> v) {
        return v.stream().map(x -> x.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void 합이_100이면_통과한다() {
        assertThat(check(10, 30, 30, 30)).isEmpty();
        assertThat(check(100, 0, 0, 0)).isEmpty();
        assertThat(check(1, 99, 0, 0)).isEmpty();
    }

    @Test
    void 합이_99_또는_101이면_FormationSum() {
        assertThat(names(check(10, 30, 30, 29))).containsExactly("FormationSum");
        assertThat(names(check(10, 30, 30, 31))).containsExactly("FormationSum");
    }

    @Test
    void 골키퍼_0은_Min이다() {
        assertThat(names(check(0, 40, 30, 30))).containsExactly("Min");
    }

    @Test
    void 칸이_범위_밖이면_Min_Max이고_빈_칸은_NotNull만_낸다() {
        assertThat(names(check(10, -1, 61, 30))).contains("Min");
        assertThat(names(check(101, 0, 0, -1))).contains("Max", "Min");
        assertThat(names(check(10, 30, 30, null))).containsExactly("NotNull");
    }

    @Test
    void 합계는_정수_범위를_넘어도_넘치지_않는다() {
        assertThat(names(check(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0))).contains("FormationSum", "Max");
    }
}
