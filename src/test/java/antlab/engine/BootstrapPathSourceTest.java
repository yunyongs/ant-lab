package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.history.ReplayMonth;
import antlab.support.FixedPathSource;
import antlab.support.IntegrationTest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/** 운영 경로 출처 + 조회 + 고정 경로 출처 — 합성 고정본(실제 표본 아님). 시험 트랜잭션이 롤백한다. */
@IntegrationTest
@Transactional
@Sql("/fixtures/replay_months.sql")
class BootstrapPathSourceTest {

    private static final LocalDate BASE = SyntheticMonths.BASE_DATE;

    @Autowired
    BootstrapPathSource source;

    @Autowired
    MonthBlockRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 조회는_하한_포함_상한_제외_달_순서() {
        List<ReplayMonth> months = repository.findReplayMonths(LocalDate.of(2007, 1, 1), LocalDate.of(2026, 1, 1));
        assertThat(months.get(0).monthStart()).isEqualTo(LocalDate.of(2007, 1, 1));
        assertThat(months.get(months.size() - 1).monthStart()).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(months).hasSize(64 + 36);
        assertThat(months).extracting(ReplayMonth::monthStart).isSorted();
    }

    @Test
    void 후보_수는_1월_시작_8_7월_시작_8() {
        // R2: 6개월 연속이 모두 있는 시작 달 수 (재생 범위 안)
        Integer jan = jdbc.queryForObject(candidateCount(1), Integer.class);
        Integer jul = jdbc.queryForObject(candidateCount(7), Integer.class);
        assertThat(jan).isEqualTo(8);
        assertThat(jul).isEqualTo(8);
    }

    private static String candidateCount(int month) {
        return "SELECT count(*) FROM month_block s WHERE EXTRACT(MONTH FROM s.month_start) = " + month
                + " AND s.month_start >= DATE '2007-01-01' AND s.month_start < DATE '2026-01-01'"
                + " AND (SELECT count(*) FROM month_block t WHERE t.month_start >= s.month_start"
                + " AND t.month_start < s.month_start + INTERVAL '6 months') = 6";
    }

    @Test
    void 운영_출처는_순수_엔진과_같은_달_경로를_낸다() {
        List<ReplayMonth> months = repository.findReplayMonths(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1));
        Map<Long, LocalDate> monthById = new HashMap<>();
        months.forEach(m -> monthById.put(m.monthBlockId(), m.monthStart()));

        for (int length : List.of(12, 24, 60)) {
            for (long seed : new long[] {1L, 14L, -77L, Long.MAX_VALUE}) {
                PathRequest req = new PathRequest(BASE, length, seed);
                GeneratedPath path = source.generate(req);
                List<LocalDate> viaDb = path.monthBlockIds().stream().map(monthById::get).toList();
                assertThat(viaDb).isEqualTo(SimpleBootstrap.generateMonths(SyntheticMonths.sample(), req));
                assertThat(source.generate(req).monthBlockIds()).isEqualTo(path.monthBlockIds());
                assertThat(path.engineVersion()).isEqualTo("p0-boot6-v1");
            }
        }
    }

    @Test
    void 골든용_고정_출처는_4_3_3_블록_10턴을_번호로_바꾼다() {
        List<LocalDate> golden = new java.util.ArrayList<>();
        SyntheticMonths.add(golden, LocalDate.of(2009, 1, 1), LocalDate.of(2009, 4, 1));
        SyntheticMonths.add(golden, LocalDate.of(2010, 5, 1), LocalDate.of(2010, 7, 1));
        SyntheticMonths.add(golden, LocalDate.of(2008, 8, 1), LocalDate.of(2008, 10, 1));
        FixedPathSource fixed = new FixedPathSource("p0-sql", golden, repository);

        GeneratedPath path = fixed.generate(new PathRequest(BASE, 10, 14L));
        assertThat(path.engineVersion()).isEqualTo("p0-sql");
        assertThat(path.monthBlockIds()).hasSize(10);
        List<LocalDate> back = path.monthBlockIds().stream()
                .map(id -> jdbc.queryForObject("SELECT month_start FROM month_block WHERE id = ?", LocalDate.class, id))
                .toList();
        assertThat(back).isEqualTo(golden);
    }

    @Test
    void 고정_출처는_길이_불일치와_규칙_위반을_거부한다() {
        List<LocalDate> golden = new java.util.ArrayList<>();
        SyntheticMonths.add(golden, LocalDate.of(2009, 1, 1), LocalDate.of(2009, 4, 1));
        FixedPathSource fixed = new FixedPathSource("p0-sql", golden, repository);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixed.generate(new PathRequest(BASE, 10, 1L)))
                .isInstanceOfSatisfying(antlab.common.error.DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(antlab.common.error.ErrorCode.INVALID_ARGUMENT));

        List<LocalDate> bad = List.of(LocalDate.of(2009, 2, 1)); // 첫 턴이 1월이 아님(S4)
        FixedPathSource wrong = new FixedPathSource("p0-sql", bad, repository);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> wrong.generate(new PathRequest(BASE, 1, 1L)))
                .isInstanceOf(IllegalStateException.class);
    }
}
