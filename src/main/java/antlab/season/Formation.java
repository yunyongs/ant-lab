package antlab.season;

import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 포메이션(포지션별 비중, 정수 퍼센트). 만들 때 DB CHECK 와 같은 규칙을 검사한다:
 * 합 100, 각 0–100, 골키퍼 1 이상. 열 이름은 쓰는 쪽({@code Season}, {@code FormationChange})이 정한다.
 */
@Embeddable
public record Formation(
        @JdbcTypeCode(SqlTypes.SMALLINT) int goalkeeper,
        @JdbcTypeCode(SqlTypes.SMALLINT) int defender,
        @JdbcTypeCode(SqlTypes.SMALLINT) int midfielder,
        @JdbcTypeCode(SqlTypes.SMALLINT) int forward) {

    public Formation {
        if (inRange(goalkeeper) && inRange(defender) && inRange(midfielder) && inRange(forward)) {
            if (goalkeeper < 1) {
                throw new IllegalArgumentException("골키퍼 비중은 1 이상이어야 한다: " + goalkeeper);
            }
            if (goalkeeper + defender + midfielder + forward != 100) {
                throw new IllegalArgumentException("포메이션 비중의 합은 100 이어야 한다");
            }
        } else {
            throw new IllegalArgumentException("포메이션 비중은 0–100 이어야 한다");
        }
    }

    private static boolean inRange(int v) {
        return v >= 0 && v <= 100;
    }
}
