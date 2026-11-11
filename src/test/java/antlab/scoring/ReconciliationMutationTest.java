package antlab.scoring;

import static antlab.scoring.ReconciliationApiSupport.seasonWithOrders;
import static antlab.scoring.ReconciliationApiSupport.seasonWithSells;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.ReconDefinitions;
import antlab.support.RuleResultAssert;
import java.util.ArrayList;
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
 * 깨뜨린 사례: API 로 만든 합성 시즌(주문 2건)에서 규칙을 하나씩 어기고(시험 트랜잭션, 끝나면 롤백) 같은 연결에서
 * ① Java = SQL({@code recon_rules}) 9컬럼 ② 불일치 코드 집합 = 사례의 기대 집합. 사례는 db/22 검증의 N1a–N18 과 같은 문장이고
 * 원장 한 행 추가·보유 수량 변경·포메이션 중복 컬럼 변경을 포함해 15개 규칙을 모두 덮는다(규칙 2 는 함의로 {2, 3}).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class ReconciliationMutationTest {

    // 사례 SQL 의 자리표시자: {S} 시즌 번호, {T} 현재 턴
    private static final String BUY_ORDER = "(SELECT min(id) FROM orders WHERE season_id = {S} AND turn_no = 0 AND side = 'BUY')";
    private static final String BUY_LEDGER = "(SELECT min(id) FROM cash_ledger WHERE season_id = {S} AND type = 'BUY')";
    private static final String FEE_ORDER = "(SELECT ref_order_id FROM cash_ledger WHERE season_id = {S} AND type = 'FEE' ORDER BY id LIMIT 1)";
    private static final String HELD_HOLDING = "(SELECT min(id) FROM holding WHERE season_id = {S} AND quantity > 0)";
    private static final String OPEN_LINEUP = "(SELECT min(id) FROM lineup WHERE season_id = {S} AND end_turn IS NULL)";
    private static final String UNHELD_ASSET = """
            (SELECT ap.asset_id FROM asset_position ap JOIN season s ON s.base_date = ap.base_date
              WHERE s.id = {S} AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.season_id = {S} AND o.asset_id = ap.asset_id)
                AND EXISTS (SELECT 1 FROM turn_price tp WHERE tp.season_id = {S} AND tp.turn_no = 0 AND tp.asset_id = ap.asset_id)
              ORDER BY ap.asset_id LIMIT 1)""";
    private static final String LATEST_BALANCE =
            "(SELECT balance_after FROM cash_ledger WHERE season_id = {S} ORDER BY id DESC LIMIT 1)";

    record Mutation(String name, String sql, Set<CheckCode> expected, String kind) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Mutation m(String name, String sql, String kind, CheckCode... expected) {
        return new Mutation(name, sql, Set.of(expected), kind);
    }

    static Stream<Arguments> mutations() {
        List<Mutation> list = new ArrayList<>();
        list.add(m("N1a 초기 자금 +1", "UPDATE season SET initial_cash = initial_cash + 1 WHERE id = {S}",
                "DEPOSIT_AMOUNT", CheckCode.ACCOUNT_OPENING));
        list.add(m("N1b 첫 원장 행 유형을 ADJUSTMENT 로",
                "UPDATE cash_ledger SET type = 'ADJUSTMENT' WHERE id = (SELECT min(id) FROM cash_ledger WHERE season_id = {S})",
                "FIRST_NOT_DEPOSIT", CheckCode.ACCOUNT_OPENING));
        list.add(m("N2 계좌 잔액 +1 (함의로 규칙 3 도)", "UPDATE cash_account SET balance = balance + 1 WHERE season_id = {S}",
                "SUM", CheckCode.CASH_SUM, CheckCode.LEDGER_CHAIN));
        list.add(m("M1' 원장 한 행 추가(주문 참조 없음, 계좌는 그대로) — 함의 사례",
                "INSERT INTO cash_ledger (season_id, type, amount, balance_after) SELECT {S}, 'ADJUSTMENT', -1, " + LATEST_BALANCE + " - 1",
                null, CheckCode.CASH_SUM, CheckCode.LEDGER_CHAIN));
        list.add(m("M1 원장 한 행 추가(주문을 참조하는 ADJUSTMENT, 사슬·잔액은 맞춤)",
                "INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) SELECT {S}, 'ADJUSTMENT', -1, "
                        + LATEST_BALANCE + " - 1, " + BUY_ORDER + "; UPDATE cash_account SET balance = balance - 1 WHERE season_id = {S}",
                "TOTAL", CheckCode.ORDER_LEDGER));
        list.add(m("N3 첫 BUY 행의 거래 후 잔액 −1",
                "UPDATE cash_ledger SET balance_after = balance_after - 1 WHERE id = " + BUY_LEDGER, "CHAIN", CheckCode.LEDGER_CHAIN));
        list.add(m("N4a BUY 행에 FEE 금액을 합치고 FEE 행을 지움(총액은 맞고 분류만 틀림)",
                "UPDATE cash_ledger b SET amount = b.amount + f.amount, balance_after = f.balance_after FROM cash_ledger f"
                        + " WHERE b.type = 'BUY' AND f.type = 'FEE' AND b.ref_order_id = f.ref_order_id AND f.ref_order_id = " + FEE_ORDER
                        + "; DELETE FROM cash_ledger WHERE type = 'FEE' AND ref_order_id = " + FEE_ORDER,
                "TYPE", CheckCode.ORDER_LEDGER));
        list.add(m("N4b BUY 금액을 1 더 줄이고 사슬·잔액을 맞춤(총액 틀림)",
                "UPDATE cash_ledger SET amount = amount - 1 WHERE id = " + BUY_LEDGER
                        + "; UPDATE cash_ledger SET balance_after = balance_after - 1 WHERE season_id = {S} AND id >= " + BUY_LEDGER
                        + "; UPDATE cash_account SET balance = balance - 1 WHERE season_id = {S}",
                "TOTAL", CheckCode.ORDER_LEDGER));
        list.add(m("N6a/M2 보유 수량 +1", "UPDATE holding SET quantity = quantity + 1 WHERE id = " + HELD_HOLDING,
                "QTY", CheckCode.HOLDING_QTY));
        list.add(m("N6b 보유 행을 지우고 열린 구간을 닫음",
                "WITH d AS (DELETE FROM holding WHERE id = " + HELD_HOLDING + " RETURNING season_id, asset_id)"
                        + " UPDATE lineup lu SET end_turn = lu.start_turn FROM d WHERE lu.season_id = d.season_id AND lu.asset_id = d.asset_id AND lu.end_turn IS NULL",
                "NO_HOLDING", CheckCode.HOLDING_QTY));
        list.add(m("N7a 주문 수량 +1", "UPDATE orders SET quantity = quantity + 1 WHERE id = " + BUY_ORDER,
                "FILL_QTY", CheckCode.ORDER_FILL));
        list.add(m("N7b 주문 상태 PENDING", "UPDATE orders SET status = 'PENDING' WHERE id = " + BUY_ORDER,
                "STATUS", CheckCode.ORDER_FILL));
        list.add(m("N8 매수 체결에 realized_pnl", "UPDATE execution SET realized_pnl = 0 WHERE order_id = " + BUY_ORDER,
                "BUY_PNL", CheckCode.EXEC_VALUES));
        list.add(m("N9 예약금 1", "UPDATE cash_account SET reserved = 1 WHERE season_id = {S}", "RESERVED", CheckCode.RESERVED_ZERO));
        list.add(m("N12a 주문 없는 풀 종목의 0턴 가격 +1",
                "UPDATE turn_price SET price = price + 1 WHERE season_id = {S} AND turn_no = 0 AND asset_id = " + UNHELD_ASSET,
                "PRICE", CheckCode.TURN_PRICE));
        list.add(m("N12b 주문 없는 풀 종목의 0턴 가격 행 삭제",
                "DELETE FROM turn_price WHERE season_id = {S} AND turn_no = 0 AND asset_id = " + UNHELD_ASSET,
                "MISSING", CheckCode.TURN_PRICE));
        list.add(m("N13 주문의 턴 번호를 현재 턴 + 1 로", "UPDATE orders SET turn_no = {T} + 1 WHERE id = " + BUY_ORDER,
                "NO_PRICE", CheckCode.FILL_PRICE));
        list.add(m("N14 국내 수수료율 +0.01", "UPDATE season SET fee_rate_kr = fee_rate_kr + 0.01 WHERE id = {S}",
                "FEE", CheckCode.FEE_RULE));
        list.add(m("N15/M3 현재 포메이션 중복 컬럼만 수비 +1·미드필더 −1",
                "UPDATE season SET current_defender = current_defender + 1, current_midfielder = current_midfielder - 1 WHERE id = {S}",
                "CURRENT", CheckCode.FORMATION));
        list.add(m("N16 복합 FK 를 지운 뒤 없는 전술 변경 번호를 채움",
                "ALTER TABLE orders DROP CONSTRAINT fk_orders_formation_change; UPDATE orders SET formation_change_id = 9223372036854775807 WHERE id = "
                        + BUY_ORDER, "WRONG", CheckCode.FORMATION_BIND));
        list.add(m("N17 보유 중인 종목의 열린 구간을 닫음", "UPDATE lineup SET end_turn = start_turn WHERE id = " + OPEN_LINEUP,
                "HOLDING_NO_OPEN", CheckCode.LINEUP_HOLDING));
        list.add(m("N18 열린 구간의 시작 턴을 현재 턴 + 1 로", "UPDATE lineup SET start_turn = {T} + 1 WHERE id = " + OPEN_LINEUP,
                "START_AFTER", CheckCode.LINEUP_OVERLAP));
        return list.stream().map(Arguments::of);
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

    private Pair compareIn(long season, int turn, String mutationSql) {
        return new TransactionTemplate(txManager).execute(status -> {
            try {
                ReconDefinitions.load(jdbc);
                if (mutationSql != null) {
                    jdbc.execute(mutationSql.replace("{S}", Long.toString(season)).replace("{T}", Integer.toString(turn)));
                }
                return new Pair(ReconDefinitions.sqlRules(jdbc, season, turn), service.evaluateManual(season, turn));
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    @Test
    void 기준_상태에서는_열다섯_개가_모두_일치하고_Java가_SQL과_같다() throws Exception {
        long season = seasonWithOrders(mvc, jdbc);
        Pair p = compareIn(season, 0, null);
        RuleResultAssert.assertSame(p.sql(), p.java());
        assertThat(p.java()).hasSize(15);
        assertThat(RuleResultAssert.mismatchCodes(p.java())).isEmpty();
        // 기준 상태가 의미 있으려면 원장·체결·보유·라인업이 실제로 있어야 한다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution e JOIN orders o ON o.id = e.order_id WHERE o.season_id = ?",
                Long.class, season)).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void 깨뜨린_사례마다_Java가_SQL과_같고_불일치_집합이_기대와_같다(Mutation mutation) throws Exception {
        long season = seasonWithOrders(mvc, jdbc);
        Pair p = compareIn(season, 0, mutation.sql());
        RuleResultAssert.assertSame(p.sql(), p.java());
        assertThat(RuleResultAssert.mismatchCodes(p.java())).as(mutation.name()).isEqualTo(mutation.expected());
        if (mutation.kind() != null) {
            assertThat(p.java().stream().filter(r -> r.detail() != null).map(RuleResult::detail).toList())
                    .as(mutation.name() + " 종류").anyMatch(d -> d.contains("\"" + mutation.kind() + "\""));
        }
        // 롤백했으니 기준 상태로 돌아와 다시 모두 일치한다
        Pair after = compareIn(season, 0, null);
        assertThat(RuleResultAssert.mismatchCodes(after.java())).isEmpty();
    }

    @Test
    void 사례_집합이_열다섯_규칙을_모두_덮는다() {
        Set<CheckCode> covered = new java.util.HashSet<>();
        mutations().forEach(a -> covered.addAll(((Mutation) a.get()[0]).expected()));
        assertThat(covered).containsAll(RuleCatalog.manualCodes());
    }

    // ---- 매도 양성 사례: 매수 2건 + 부분 매도(국내) + 전량 매도(미국)가 있는 기준 상태 ----

    private static final String SELL_ORDER = "(SELECT min(id) FROM orders WHERE season_id = {S} AND side = 'SELL')";
    private static final String SELL_LEDGER = "(SELECT min(id) FROM cash_ledger WHERE season_id = {S} AND type = 'SELL')";
    private static final String CLOSED_LINEUP = "(SELECT min(id) FROM lineup WHERE season_id = {S} AND end_turn IS NOT NULL)";

    static Stream<Arguments> sellMutations() {
        return Stream.of(
                m("N8b 매도 체결의 realized_pnl 을 비움", "UPDATE execution SET realized_pnl = NULL WHERE order_id = " + SELL_ORDER,
                        "SELL_PNL", CheckCode.EXEC_VALUES),
                m("N17b 전량 매도 종목의 닫힌 구간을 다시 염", "UPDATE lineup SET end_turn = NULL WHERE id = " + CLOSED_LINEUP,
                        "OPEN_NO_HOLDING", CheckCode.LINEUP_HOLDING),
                // 유형을 BUY 로 바꾸면 부호 CHECK(ck_cash_ledger_amount_sign)가 먼저 막는다 — 부호가 자유로운 ADJUSTMENT 로 바꾼다
                m("N4c 매도 원장 행의 유형을 ADJUSTMENT 로(총액은 맞고 분류만 틀림)",
                        "UPDATE cash_ledger SET type = 'ADJUSTMENT' WHERE id = " + SELL_LEDGER, "TYPE", CheckCode.ORDER_LEDGER)).map(Arguments::of);
    }

    @Test
    void 매도가_낀_기준_상태에서도_열다섯_개가_모두_일치하고_Java가_SQL과_같다() throws Exception {
        long season = seasonWithSells(mvc, jdbc);
        Pair p = compareIn(season, 0, null);
        RuleResultAssert.assertSame(p.sql(), p.java());
        assertThat(p.java()).hasSize(15);
        assertThat(RuleResultAssert.mismatchCodes(p.java())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution e JOIN orders o ON o.id = e.order_id"
                + " WHERE o.season_id = ? AND o.side = 'SELL' AND e.realized_pnl IS NOT NULL", Long.class, season))
                .as("매도 체결 2건이 실제로 있다").isEqualTo(2L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sellMutations")
    void 매도_사례를_깨뜨리면_Java가_SQL과_같고_불일치_집합이_기대와_같다(Mutation mutation) throws Exception {
        long season = seasonWithSells(mvc, jdbc);
        Pair p = compareIn(season, 0, mutation.sql());
        RuleResultAssert.assertSame(p.sql(), p.java());
        System.out.println("[X6] " + mutation.name() + " → "
                + p.java().stream().filter(r -> r.detail() != null).map(RuleResult::detail).toList());
        assertThat(RuleResultAssert.mismatchCodes(p.java())).as(mutation.name()).isEqualTo(mutation.expected());
        assertThat(p.java().stream().filter(r -> r.detail() != null).map(RuleResult::detail).toList())
                .as(mutation.name() + " 종류").anyMatch(d -> d.contains("\"" + mutation.kind() + "\""));
        assertThat(RuleResultAssert.mismatchCodes(compareIn(season, 0, null).java())).isEmpty();
    }
}
