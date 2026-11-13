package antlab.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

class UniqueViolationsTest {

    private static final String UNIQUE = "uq_member_email";

    private static ConstraintViolationException hibernate(String sqlState, String constraint) {
        return new ConstraintViolationException("x", new SQLException("x", sqlState), constraint);
    }

    @Test
    void 이름과_SQLState가_맞으면_참() {
        assertThat(UniqueViolations.isViolationOf(hibernate("23505", UNIQUE), UNIQUE)).isTrue();
        assertThat(UniqueViolations.isViolationOf(new RuntimeException(hibernate("23505", "UQ_MEMBER_EMAIL")), UNIQUE)).isTrue();
    }

    @Test
    void 다른_제약이거나_다른_SQLState면_거짓() {
        assertThat(UniqueViolations.isViolationOf(hibernate("23505", "uq_other"), UNIQUE)).isFalse();
        assertThat(UniqueViolations.isViolationOf(hibernate("23514", UNIQUE), UNIQUE)).isFalse();
    }

    @Test
    void 중첩된_SQLException의_주요_메시지에_제약_이름이_있으면_참() {
        SQLException sql = new SQLException("duplicate key value violates unique constraint \"uq_member_email\"", "23505");
        assertThat(UniqueViolations.isViolationOf(new RuntimeException(new RuntimeException(sql)), UNIQUE)).isTrue();
        assertThat(UniqueViolations.isViolationOf(new SQLException("uq_member_email", "23514"), UNIQUE)).isFalse();
    }
}
