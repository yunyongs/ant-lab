package antlab.engine;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 경로 요청. baseDate = season.base_date(= antlab.pool.base-date), length = path_length, seed = season.seed.
 * 시드는 시즌 종료 전 응답·로그에 내지 않는 값이라 toString 에서 뺀다.
 */
public record PathRequest(LocalDate baseDate, int length, long seed) {

    public PathRequest {
        Objects.requireNonNull(baseDate, "baseDate");
    }

    @Override
    public String toString() {
        return "PathRequest[" + length + "턴]";
    }
}
