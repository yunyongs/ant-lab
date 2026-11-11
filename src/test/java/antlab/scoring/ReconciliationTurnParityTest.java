package antlab.scoring;

import static antlab.api.turn.TurnParitySupport.advanceOnce;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.ReconDefinitions;
import antlab.support.RuleResultAssert;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 연결·같은 상태에서 Java 평가({@code evaluateTurn}) 17행과 SQL 정의({@code pg_temp.recon_rules_turn}) 17행의 9컬럼이 같다.
 * 상태는 실제 HTTP 전환으로 만든 합성 시즌(커밋)이고, 깨진 사례는 평가 트랜잭션 안에서만 만들고 되돌린다.
 * 기대 불일치 집합은 이 파일에 손으로 적지 않는다 — SQL 이 같은 상태에서 낸 집합과 Java 가 낸 집합이 같음을 본다(설계 의도 단언만 따로 둔다).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class ReconciliationTurnParityTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReconciliationService service;
    @Autowired
    PlatformTransactionManager txManager;

    private record Compared(List<RuleResult> sql, List<RuleResult> java) {
    }

    private static Set<Integer> mismatched(List<RuleResult> rows) {
        return rows.stream().filter(r -> r.status() == ReconciliationStatus.MISMATCH).map(RuleResult::ruleNo)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 트랜잭션 안에서 상태를 깨뜨리고(없으면 그대로) 두 쪽을 읽은 뒤 롤백한다. */
    private Compared compare(long season, int turn, Consumer<JdbcTemplate> breakState) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        return template.execute(status -> {
            status.setRollbackOnly();
            ReconDefinitions.loadTurn(jdbc);
            breakState.accept(jdbc);
            List<RuleResult> java = service.evaluateTurn(season, turn);
            List<RuleResult> sql = ReconDefinitions.sqlTurnRules(jdbc, season, turn);
            return new Compared(sql, java);
        });
    }

    @Test
    void 일_턴_전환_직후_Java_17행과_recon_rules_turn_17행의_9컬럼이_같다() throws Exception {
        long season = advanceOnce(mvc, jdbc);
        Compared c = compare(season, 1, j -> { });
        assertThat(c.sql()).hasSize(17);
        assertThat(c.java()).hasSize(17);
        RuleResultAssert.assertSame(c.sql(), c.java());
        assertThat(mismatched(c.java())).isEmpty();
        assertThat(c.java().stream().map(RuleResult::ruleNo).toList()).isSorted().contains(11, 19);
    }

    @Test
    void 깨뜨린_상태에서도_두_쪽의_불일치_규칙_집합이_같다() throws Exception {
        long season = advanceOnce(mvc, jdbc);

        Compared holdings = compare(season, 1, j -> j.update("UPDATE turn SET holdings_value = holdings_value + 1,"
                + " total_value = total_value + 1, goalkeeper_value = goalkeeper_value + 1"
                + " WHERE season_id = ? AND turn_no = 1", season));
        RuleResultAssert.assertSame(holdings.sql(), holdings.java());
        System.out.println("[T3] 보유 평가액을 깨뜨린 사례 불일치 규칙 = " + mismatched(holdings.java()));
        assertThat(mismatched(holdings.java())).isEqualTo(Set.of(11, 19));

        Compared lineup = compare(season, 1, j -> j.update("UPDATE lineup SET end_turn = 0 WHERE id = (SELECT min(id) FROM lineup"
                + " WHERE season_id = ?)", season));
        RuleResultAssert.assertSame(lineup.sql(), lineup.java());
        System.out.println("[T3] 라인업 구간을 닫은 사례 불일치 규칙 = " + mismatched(lineup.java()));
        assertThat(mismatched(lineup.java())).isEqualTo(Set.of(17, 19));

        Compared position = compare(season, 1, j -> j.update("UPDATE turn SET defender_value = defender_value + 1,"
                + " goalkeeper_value = goalkeeper_value - 1 WHERE season_id = ? AND turn_no = 1", season));
        RuleResultAssert.assertSame(position.sql(), position.java());
        System.out.println("[T3] 포지션별 평가액을 깨뜨린 사례 불일치 규칙 = " + mismatched(position.java()));
        assertThat(mismatched(position.java())).isEqualTo(Set.of(19));
    }
}
