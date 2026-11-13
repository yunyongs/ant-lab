package antlab.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaxUtf8BytesValidatorTest {

    private static MaxUtf8BytesValidator validator() {
        MaxUtf8BytesValidator v = new MaxUtf8BytesValidator();
        v.initialize(new MaxUtf8Bytes() {
            @Override
            public int value() {
                return 72;
            }

            @Override
            public String message() {
                return "";
            }

            @Override
            public Class<?>[] groups() {
                return new Class<?>[0];
            }

            @Override
            @SuppressWarnings("unchecked")
            public Class<? extends jakarta.validation.Payload>[] payload() {
                return new Class[0];
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return MaxUtf8Bytes.class;
            }
        });
        return v;
    }

    @Test
    void 경계는_72바이트까지_허용하고_73바이트는_거절한다() {
        assertThat(validator().isValid("a".repeat(72), null)).isTrue();
        assertThat(validator().isValid("a".repeat(73), null)).isFalse();
        assertThat(validator().isValid("가".repeat(24), null)).isTrue();
        assertThat(validator().isValid("가".repeat(24) + "a", null)).isFalse();
    }

    @Test
    void null은_통과시킨다() {
        assertThat(validator().isValid(null, null)).isTrue();
    }
}
