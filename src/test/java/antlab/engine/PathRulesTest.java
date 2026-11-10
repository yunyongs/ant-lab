package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathRulesTest {

    private static final LocalDate BASE = SyntheticMonths.BASE_DATE;

    private static List<LocalDate> run(int year, int month, int count) {
        List<LocalDate> result = new ArrayList<>();
        LocalDate start = LocalDate.of(year, month, 1);
        for (int i = 0; i < count; i++) {
            result.add(start.plusMonths(i));
        }
        return result;
    }

    @SafeVarargs
    private static List<LocalDate> path(List<LocalDate>... parts) {
        List<LocalDate> result = new ArrayList<>();
        for (List<LocalDate> p : parts) {
            result.addAll(p);
        }
        return result;
    }

    @Test
    void 올바른_12턴_경로는_위반이_없다() {
        List<LocalDate> ok = path(run(2009, 1, 6), run(2011, 7, 6));
        assertThat(PathRules.violations(ok, BASE, 12, 6)).isEmpty();
        assertThat(PathRules.violations(ok, BASE, 12)).isEmpty();
    }

    @Test
    void 골든_손_경로_모양_4_3_3은_블록_길이를_모르는_검사를_통과한다() {
        // 1월 시작 4달, 5월 시작 3달, 8월 시작 3달 — 턴의 달은 1..10월
        List<LocalDate> golden = path(run(2009, 1, 4), run(2010, 5, 3), run(2008, 8, 3));
        assertThat(PathRules.violations(golden, BASE, 10)).isEmpty();
    }

    @Test
    void 행_수가_길이와_다르면_S1_COUNT() {
        assertThat(PathRules.violations(path(run(2009, 1, 6), run(2011, 7, 6)), BASE, 24)).contains("S1_COUNT");
    }

    @Test
    void 범위_밖_달은_S1_RANGE() {
        List<LocalDate> early = path(run(2006, 12, 1), run(2007, 1, 5), run(2011, 7, 6));
        assertThat(PathRules.violations(early, BASE, 12)).contains("S1_RANGE");
        List<LocalDate> late = path(run(2009, 1, 6), run(2025, 7, 6));
        assertThat(PathRules.violations(late, BASE, 12)).isEmpty();
        assertThat(PathRules.violations(late, LocalDate.of(2025, 1, 2), 12)).contains("S1_RANGE");
    }

    @Test
    void 열세_번째_턴이_1월이_아니면_S4() {
        List<LocalDate> bad = path(run(2009, 1, 6), run(2011, 7, 6), run(2010, 2, 6), run(2008, 8, 6));
        assertThat(PathRules.violations(bad, BASE, 24)).contains("S4");
    }

    @Test
    void 같은_달을_두_번_쓰면_REUSE() {
        List<LocalDate> bad = path(run(2009, 1, 6), run(2011, 7, 6), run(2009, 1, 6), run(2011, 7, 6));
        assertThat(PathRules.violations(bad, BASE, 24)).contains("REUSE");
    }

    @Test
    void 끊긴_곳을_블록_안에서_넘으면_BLOCK_GAP() {
        // 2012-04 다음 턴이 2023-01 — 블록 한가운데에서 달이 이어지지 않는다
        List<LocalDate> bad = path(run(2012, 1, 4), run(2023, 1, 2), run(2011, 7, 6));
        assertThat(PathRules.violations(bad, BASE, 12, 6)).contains("BLOCK_GAP");
    }

    @Test
    void 블록_경계가_역사_그대로_이어지면_BLOCK_JOIN() {
        List<LocalDate> bad = path(run(2009, 1, 12));
        assertThat(PathRules.violations(bad, BASE, 12, 6)).containsExactly("BLOCK_JOIN");
        assertThat(PathRules.violations(bad, BASE, 12)).isEmpty();
    }
}
