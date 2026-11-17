package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The digest text is fixed by the engine ({@code api/paths.py}: version, then the sorted replayable months, joined with
 * line feeds, no trailing one). The literals below were computed with Python's hashlib over exactly that text, so a Java
 * change of the layout cannot go unnoticed.
 */
class HistoryDigestTest {

    private static final LocalDate BASE = LocalDate.of(2026, 1, 2);
    private static final String M2 = "m2-sb6-v1";
    private static final String THREE_MONTHS_M2 = "58f5d5165e1de885a9688fce415e0fc04ced6e83e7b8ebc240e3590c002a240e";

    private static List<LocalDate> months(String... yearMonths) {
        return Arrays.stream(yearMonths).map(text -> LocalDate.parse(text + "-01")).toList();
    }

    @Test
    void 엔진과_같은_글자로_해시한다() {
        assertThat(HistoryDigest.of(M2, months("2007-01", "2007-02", "2025-12"), BASE)).isEqualTo(THREE_MONTHS_M2);
        assertThat(HistoryDigest.of("p0-boot6-v1", months("2007-01", "2007-02", "2025-12"), BASE))
                .isEqualTo("ba8c28034074353b63ae39c327c3527065540a524076c533abca28ad1a4015cd");
    }

    @Test
    void 범위_밖_달은_해시에_들어가지_않는다() {
        // 2006-12 is before the replay start, 2026-01 is the base year's January (the exclusive upper bound)
        assertThat(HistoryDigest.of(M2, months("2006-12", "2007-01", "2007-02", "2025-12", "2026-01"), BASE))
                .isEqualTo(THREE_MONTHS_M2);
    }

    @Test
    void 기준일_해의_앞_달은_들어간다() {
        // base year 2027: 2026-01 is replayable and changes the digest
        assertThat(HistoryDigest.of(M2, months("2007-01", "2007-02", "2025-12", "2026-01"), LocalDate.of(2027, 1, 4)))
                .isEqualTo("bc96f363c5ea1dadd38dcea404ca4662707e7aca9bbecb3f07a4f7731fb3edc7");
    }

    @Test
    void 입력_순서와_중복은_결과에_영향이_없다() {
        assertThat(HistoryDigest.of(M2, months("2025-12", "2007-02", "2007-01", "2007-02"), BASE))
                .isEqualTo(THREE_MONTHS_M2);
    }

    @Test
    void 달이_하나_다르면_다른_해시다() {
        assertThat(HistoryDigest.of(M2, months("2007-01", "2007-02"), BASE))
                .isEqualTo("a32ee84b2d59e51634ae8c7fd9f8c0c9118946408ffab1058455230309f5c727")
                .isNotEqualTo(THREE_MONTHS_M2);
    }

    @Test
    void 달이_없으면_버전_글자만_해시한다() {
        assertThat(HistoryDigest.of(M2, List.of(), BASE))
                .isEqualTo("70622c014c48fd7c77336b7670f6f95790fccdda52457241f6545e23f519ea60");
    }
}
