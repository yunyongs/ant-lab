package antlab.scoring;

import static antlab.api.formation.FormationApiSupportAccess.A;
import static antlab.api.formation.FormationApiSupportAccess.B;
import static antlab.api.formation.FormationApiSupportAccess.C;
import static antlab.api.formation.FormationApiSupportAccess.change;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buy;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.ReconDefinitions;
import antlab.support.RuleResultAssert;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 규칙 15(현재 포메이션 ↔ 변경 사슬)·16(주문의 변경 연결)의 양성과 변이 사례 — 변경·연결 행이 <b>실제로 있는</b> 시즌에서.
 * 기준 시즌(1턴): 변경 A → 주문 1 → 변경 B → 주문 2 → 변경 C(주문 없음), 모두 API 로 만든다. 사례마다 시험 트랜잭션 안에서 한 곳을 깨뜨리고
 * 같은 연결에서 ① Java = SQL({@code recon_rules}) 9컬럼 ② 불일치 코드 집합 = 기대 집합을 확인한 뒤 롤백한다.
 *
 * <p>Java 는 같은 턴의 마지막 변경을 <b>번호</b> 순으로, SQL 규칙 16 은 <b>기록 시각</b> 순으로 읽는다. 정상 쓰기(시즌 잠금 + 시각 가드)에서는 둘이
 * 같은 순서이며(첫 시험이 확인한다), 어긋난 변조 데이터는 규칙 16 이 잡는다("R2 시각·번호 순서 뒤바뀜" 사례).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class FormationBindMutationTest {

    // 사례 SQL 의 자리표시자: {S} 시즌 번호
    private static final String CHANGE_A = "(SELECT id FROM formation_change WHERE season_id = {S} ORDER BY id LIMIT 1)";
    private static final String CHANGE_B = "(SELECT id FROM formation_change WHERE season_id = {S} ORDER BY id OFFSET 1 LIMIT 1)";
    private static final String CHANGE_C = "(SELECT id FROM formation_change WHERE season_id = {S} ORDER BY id OFFSET 2 LIMIT 1)";
    private static final String ORDER_1 = "(SELECT id FROM orders WHERE season_id = {S} AND turn_no = 1 ORDER BY id LIMIT 1)";
    private static final String ORDER_2 = "(SELECT id FROM orders WHERE season_id = {S} AND turn_no = 1 ORDER BY id OFFSET 1 LIMIT 1)";

    record Mutation(String name, String sql, CheckCode expected, String kind) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> mutations() {
        return Stream.of(
                new Mutation("규칙 15: 현재 포메이션 중복 컬럼만 수비 +1·미드필더 −1",
                        "UPDATE season SET current_defender = current_defender + 1, current_midfielder = current_midfielder - 1 WHERE id = {S}",
                        CheckCode.FORMATION, "CURRENT"),
                new Mutation("규칙 15: 마지막 변경의 이후 값만 바뀜(현재와 불일치)",
                        "UPDATE formation_change SET after_goalkeeper = 30, after_defender = 20, after_midfielder = 30, after_forward = 20 WHERE id = "
                                + CHANGE_C, CheckCode.FORMATION, "CURRENT"),
                new Mutation("규칙 15: 가운데 변경의 이전 값이 직전 변경의 이후 값과 다름(사슬 끊김)",
                        "UPDATE formation_change SET before_goalkeeper = 25, before_defender = 25, before_midfielder = 25, before_forward = 25 WHERE id = "
                                + CHANGE_B, CheckCode.FORMATION, null),
                new Mutation("규칙 16: 변경 뒤 첫 주문의 연결이 빠짐(UNBOUND)",
                        "UPDATE orders SET formation_change_id = NULL WHERE id = " + ORDER_1, CheckCode.FORMATION_BIND, "UNBOUND"),
                new Mutation("규칙 16: 둘째 주문이 이전 변경에 묶임(WRONG)",
                        "UPDATE orders SET formation_change_id = " + CHANGE_A + " WHERE id = " + ORDER_2, CheckCode.FORMATION_BIND, "WRONG"),
                new Mutation("규칙 16: 둘째 주문이 뒤 변경에 묶임(WRONG — 사후 연결 금지)",
                        "UPDATE orders SET formation_change_id = " + CHANGE_C + " WHERE id = " + ORDER_2, CheckCode.FORMATION_BIND, "WRONG"),
                new Mutation("규칙 16: 변경과 주문의 기록 시각이 같음(TIE)",
                        "UPDATE formation_change SET created_at = (SELECT created_at FROM orders WHERE id = " + ORDER_2 + ") WHERE id = "
                                + CHANGE_B, CheckCode.FORMATION_BIND, "TIE"),
                new Mutation("R2 시각·번호 순서 뒤바뀜: 변경 B 와 C 의 기록 시각을 맞바꿈 — 번호 순 연결이 시각 순 기대와 어긋남",
                        "UPDATE formation_change f SET created_at = x.t FROM (SELECT id, "
                                + "CASE WHEN id = " + CHANGE_B + " THEN (SELECT created_at FROM formation_change WHERE id = " + CHANGE_C + ") "
                                + "ELSE (SELECT created_at FROM formation_change WHERE id = " + CHANGE_B + ") END AS t "
                                + "FROM formation_change WHERE id IN (" + CHANGE_B + ", " + CHANGE_C + ")) x WHERE f.id = x.id",
                        CheckCode.FORMATION_BIND, "WRONG")).map(Arguments::of);
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    ReconciliationService service;

    private record Pair(List<RuleResult> sql, List<RuleResult> java) {
    }

    private long baseSeason() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(change(mvc, season, 1, A).getStatus()).isEqualTo(201);
        buy(mvc, jdbc, season, 1, "069500", 1);
        assertThat(change(mvc, season, 1, B).getStatus()).isEqualTo(201);
        buy(mvc, jdbc, season, 1, "069500", 1);
        assertThat(change(mvc, season, 1, C).getStatus()).isEqualTo(201);
        return season;
    }

    private Pair compareIn(long season, String mutationSql) {
        return new TransactionTemplate(txManager).execute(status -> {
            try {
                ReconDefinitions.load(jdbc);
                if (mutationSql != null) {
                    jdbc.execute(mutationSql.replace("{S}", Long.toString(season)));
                }
                return new Pair(ReconDefinitions.sqlRules(jdbc, season, 1), service.evaluateManual(season, 1));
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    @Test
    void 기준_시즌에서는_규칙_15와_16이_변경과_연결_행이_있는_채로_일치하고_번호_순서가_시각_순서와_같다() throws Exception {
        long season = baseSeason();
        // 의미 있는 기준: 변경 3개, 변경 뒤 주문 2건이 서로 다른 변경에 묶임, 마지막 변경은 주문이 없음
        assertThat(jdbc.queryForObject("SELECT count(*) FROM formation_change WHERE season_id = ?", Long.class, season)).isEqualTo(3L);
        assertThat(jdbc.queryForList("SELECT formation_change_id FROM orders WHERE season_id = ? AND turn_no = 1 ORDER BY id", Long.class, season))
                .hasSize(2).doesNotContainNull().doesNotHaveDuplicates();
        // 정상 쓰기: 변경과 주문 모두 번호 순서 = 기록 시각 순서 (Java 번호 정렬 = SQL 시각 정렬이 되는 전제)
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM formation_change x JOIN formation_change y ON y.season_id = x.season_id AND y.turn_no = x.turn_no
                WHERE x.season_id = ? AND x.id < y.id AND x.created_at >= y.created_at""", Long.class, season)).isZero();
        // 주문도 같다: 각 주문이 묶인 변경은 그 주문보다 앞서 기록된 같은 턴 변경 중 번호가 가장 큰 것이다(= 시각이 가장 늦은 것)
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM orders o WHERE o.season_id = ? AND o.turn_no = 1 AND o.formation_change_id IS DISTINCT FROM
                  (SELECT max(f.id) FROM formation_change f WHERE f.season_id = o.season_id AND f.turn_no = o.turn_no AND f.created_at < o.created_at)""",
                Long.class, season)).isZero();
        Pair p = compareIn(season, null);
        RuleResultAssert.assertSame(p.sql(), p.java());
        assertThat(p.java()).hasSize(15);
        assertThat(RuleResultAssert.mismatchCodes(p.java())).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void 깨뜨린_사례마다_Java가_SQL과_같고_불일치_집합이_기대와_같다(Mutation mutation) throws Exception {
        long season = baseSeason();
        Pair p = compareIn(season, mutation.sql());
        RuleResultAssert.assertSame(p.sql(), p.java());
        assertThat(RuleResultAssert.mismatchCodes(p.java())).as(mutation.name()).isEqualTo(Set.of(mutation.expected()));
        if (mutation.kind() != null) {
            assertThat(p.java().stream().filter(r -> r.detail() != null).map(RuleResult::detail).toList())
                    .as(mutation.name() + " 종류").anyMatch(d -> d.contains("\"" + mutation.kind() + "\""));
        }
        // 롤백했으니 기준 상태로 돌아와 다시 모두 일치한다
        assertThat(RuleResultAssert.mismatchCodes(compareIn(season, null).java())).isEmpty();
    }

    @Test
    void 사례_집합이_규칙_15와_16을_모두_덮는다() {
        Set<CheckCode> covered = new java.util.HashSet<>();
        mutations().forEach(a -> covered.add(((Mutation) a.get()[0]).expected()));
        assertThat(covered).containsExactlyInAnyOrder(CheckCode.FORMATION, CheckCode.FORMATION_BIND);
    }
}
