package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.history.ReplayMonth;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimpleBootstrapTest {

    private static final LocalDate BASE = SyntheticMonths.BASE_DATE;
    private static final LocalDate CUT = LocalDate.of(2012, 4, 1);
    private static final LocalDate RESUME = LocalDate.of(2023, 1, 1);

    private static List<Long> seeds() {
        List<Long> seeds = new ArrayList<>();
        for (long s = -500; s < 500; s++) {
            seeds.add(s);
        }
        seeds.add(Long.MIN_VALUE);
        seeds.add(Long.MAX_VALUE);
        return seeds;
    }

    @Test
    void 같은_입력은_같은_경로_입력_순서와_번호는_결과에_영향이_없다() {
        List<ReplayMonth> months = SyntheticMonths.sample();
        PathRequest req = new PathRequest(BASE, 24, 42L);
        List<LocalDate> first = SimpleBootstrap.generateMonths(months, req);
        assertThat(SimpleBootstrap.generateMonths(months, req)).isEqualTo(first);

        List<ReplayMonth> shuffled = new ArrayList<>(months);
        Collections.shuffle(shuffled, new java.util.Random(5));
        assertThat(SimpleBootstrap.generateMonths(shuffled, req)).isEqualTo(first);

        // 번호를 거꾸로 붙여도 달은 같다
        List<LocalDate> shape = SyntheticMonths.sampleShape();
        List<ReplayMonth> reversedIds = new ArrayList<>();
        for (int i = 0; i < shape.size(); i++) {
            reversedIds.add(new ReplayMonth(9000L - i, shape.get(i)));
        }
        assertThat(SimpleBootstrap.generateMonths(reversedIds, req)).isEqualTo(first);
    }

    @Test
    void 번호_목록은_달_목록을_번호로_바꾼_것이다() {
        List<ReplayMonth> months = SyntheticMonths.sample();
        Map<LocalDate, Long> idByMonth = new HashMap<>();
        months.forEach(m -> idByMonth.put(m.monthStart(), m.monthBlockId()));
        PathRequest req = new PathRequest(BASE, 60, 9L);
        GeneratedPath path = SimpleBootstrap.generate(months, req);
        List<Long> expected = SimpleBootstrap.generateMonths(months, req).stream().map(idByMonth::get).toList();
        assertThat(path.monthBlockIds()).isEqualTo(expected);
        assertThat(path.engineVersion()).isEqualTo("p0-boot6-v1");
    }

    @Test
    void 시드_1000개_x_길이_12_24_60에서_규칙_위반이_없다() {
        List<LocalDate> shape = SyntheticMonths.sampleShape();
        Set<LocalDate> present = new HashSet<>(shape);
        List<ReplayMonth> months = SyntheticMonths.withIds(shape);
        for (int length : List.of(12, 24, 60)) {
            for (long seed : seeds()) {
                List<LocalDate> path = SimpleBootstrap.generateMonths(months, new PathRequest(BASE, length, seed));
                String where = "length=" + length + " seed=" + seed;
                assertThat(path).as(where).hasSize(length);
                assertThat(PathRules.violations(path, BASE, length, 6)).as(where).isEmpty();
                // 검사기와 따로 쓴 독립 단언
                assertThat(new HashSet<>(path)).as("재사용 " + where).hasSize(length);
                for (int k = 0; k < length; k++) {
                    LocalDate m = path.get(k);
                    assertThat(m.getMonthValue()).as("S4 " + where).isEqualTo((k % 12) + 1);
                    assertThat(m).as("S1 하한 " + where).isAfterOrEqualTo(LocalDate.of(2007, 1, 1));
                    assertThat(m).as("S1 상한 " + where).isBefore(LocalDate.of(2026, 1, 1));
                    assertThat(present).as("표본에 있는 달 " + where).contains(m);
                    if (k % 6 != 0) {
                        assertThat(m).as("블록 안 연속 " + where).isEqualTo(path.get(k - 1).plusMonths(1));
                    } else if (k > 0) {
                        assertThat(m).as("역사 이어 붙이기 금지 " + where).isNotEqualTo(path.get(k - 1).plusMonths(1));
                    }
                    // 끊긴 곳(2012-04 → 2023-01)을 넘는 블록이 없다
                    if (k % 6 != 0) {
                        boolean crosses = path.get(k - 1).equals(CUT) && m.equals(RESUME);
                        assertThat(crosses).as("끊긴 곳 " + where).isFalse();
                    }
                }
            }
        }
    }

    @Test
    void 서로_다른_시드가_서로_다른_경로를_내고_첫_슬롯_후보_8개가_모두_뽑힌다() {
        List<ReplayMonth> months = SyntheticMonths.sample();
        Set<List<LocalDate>> distinct = new HashSet<>();
        Set<LocalDate> firstBlocks = new HashSet<>();
        for (long seed : seeds()) {
            List<LocalDate> path = SimpleBootstrap.generateMonths(months, new PathRequest(BASE, 12, seed));
            distinct.add(path);
            firstBlocks.add(path.get(0));
        }
        assertThat(distinct.size()).isGreaterThanOrEqualTo(50);
        assertThat(firstBlocks).hasSize(8);
    }

    @Test
    void 허용_길이_밖은_거부한다() {
        List<ReplayMonth> months = SyntheticMonths.sample();
        for (int bad : new int[] {0, -1, 10, 13, 59}) {
            assertThatThrownBy(() -> SimpleBootstrap.generate(months, new PathRequest(BASE, bad, 1L)))
                    .isInstanceOfSatisfying(DomainException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
        }
        for (int tooLong : new int[] {61, 120}) {
            assertThatThrownBy(() -> SimpleBootstrap.generate(months, new PathRequest(BASE, tooLong, 1L)))
                    .isInstanceOfSatisfying(DomainException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(ErrorCode.PATH_TOO_LONG);
                        assertThat(e.getMessage()).doesNotContainPattern("20\\d\\d");
                    });
        }
    }

    @Test
    void 데이터가_모자라면_PATH_TOO_LONG() {
        List<LocalDate> recent = new ArrayList<>();
        SyntheticMonths.add(recent, LocalDate.of(2023, 1, 1), LocalDate.of(2025, 12, 1));
        List<ReplayMonth> months = SyntheticMonths.withIds(recent);
        assertThatThrownBy(() -> SimpleBootstrap.generate(months, new PathRequest(BASE, 60, 1L)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> {
                            assertThat(e.getCode()).isEqualTo(ErrorCode.PATH_TOO_LONG);
                            assertThat(e.getMessage()).doesNotContainPattern("20\\d\\d");
                        });
        // 같은 목록으로 12턴은 만들 수 있다(3년 = 후보 3 + 3)
        assertThat(SimpleBootstrap.generateMonths(months, new PathRequest(BASE, 12, 1L))).hasSize(12);
    }

    @Test
    void 이어_붙이기_금지로_후보가_소진되면_PATH_TOO_LONG() {
        // 2009 한 해: 첫 슬롯 후보는 2009-01뿐, 둘째 슬롯 유일 후보 2009-07 은 이전 블록 바로 뒤(prevEnd + 1)라 빠진다
        List<LocalDate> oneYear = new ArrayList<>();
        SyntheticMonths.add(oneYear, LocalDate.of(2009, 1, 1), LocalDate.of(2009, 12, 1));
        List<ReplayMonth> months = SyntheticMonths.withIds(oneYear);
        assertThatThrownBy(() -> SimpleBootstrap.generate(months, new PathRequest(BASE, 12, 1L)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PATH_TOO_LONG));
        // 빈 목록은 첫 슬롯부터 소진
        assertThatThrownBy(() -> SimpleBootstrap.generate(List.of(), new PathRequest(BASE, 12, 1L)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PATH_TOO_LONG));
    }

    @Test
    void 재생_범위_밖의_완전한_해가_더_있어도_결과가_같다() {
        // 2006 한 해(하한 밖)와 2026-02–06(상한 밖, 2026-01 블록이 완전해짐)을 더해도 필터가 맞으면 결과가 같다
        List<LocalDate> wide = new ArrayList<>();
        SyntheticMonths.add(wide, LocalDate.of(2006, 1, 1), LocalDate.of(2006, 11, 1));
        wide.addAll(SyntheticMonths.sampleShape());
        SyntheticMonths.add(wide, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 6, 1));
        List<ReplayMonth> widened = SyntheticMonths.withIds(wide);
        List<ReplayMonth> plain = SyntheticMonths.sample();
        for (int length : new int[] {12, 24, 60}) {
            for (long seed : seeds()) {
                PathRequest req = new PathRequest(BASE, length, seed);
                List<LocalDate> got = SimpleBootstrap.generateMonths(widened, req);
                assertThat(got).isEqualTo(SimpleBootstrap.generateMonths(plain, req));
                for (LocalDate m : got) {
                    assertThat(m).isAfterOrEqualTo(LocalDate.of(2007, 1, 1)).isBefore(LocalDate.of(2026, 1, 1));
                }
            }
        }
    }

    @Test
    void 기준일_해의_달은_나오지_않는다() {
        List<ReplayMonth> months = SyntheticMonths.sample();
        LocalDate base2025 = LocalDate.of(2025, 1, 2);
        for (long seed : seeds()) {
            for (LocalDate m : SimpleBootstrap.generateMonths(months, new PathRequest(base2025, 12, seed))) {
                assertThat(m).isBefore(LocalDate.of(2025, 1, 1));
            }
        }
    }

    @Test
    void 월_목록_오류는_버그로_취급한다() {
        List<ReplayMonth> dup = new ArrayList<>(SyntheticMonths.sample());
        dup.add(new ReplayMonth(5L, LocalDate.of(2009, 1, 1)));
        assertThatThrownBy(() -> SimpleBootstrap.generate(dup, new PathRequest(BASE, 12, 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("2009");
        List<ReplayMonth> notFirst = new ArrayList<>(SyntheticMonths.sample());
        notFirst.add(new ReplayMonth(6L, LocalDate.of(2009, 1, 2)));
        assertThatThrownBy(() -> SimpleBootstrap.generate(notFirst, new PathRequest(BASE, 12, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 요청과_결과의_문자열에는_시드와_번호가_없다() {
        PathRequest req = new PathRequest(BASE, 12, 123456789L);
        assertThat(req.toString()).doesNotContain("123456789");
        GeneratedPath path = SimpleBootstrap.generate(SyntheticMonths.sample(), req);
        assertThat(path.toString()).isEqualTo("GeneratedPath[12턴, p0-boot6-v1]");
    }
}
