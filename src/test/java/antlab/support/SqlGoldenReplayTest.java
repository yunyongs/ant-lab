package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.GoldenRowQueries.GoldenTable;
import antlab.support.RowSetDiff.Kind;
import antlab.support.SeasonReplayInput.ActionKind;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 같은 입력 묶음을 독립된 빈 DB 두 개에 적용해 SQL 의 재현성을 확인한다.
 * 이것은 "같은 SQL 이 같은 결과를 낸다"는 보장이고, 업무 공식의 정당성은 20·21·23 검증(독립 계산)이 따로 본다.
 * 실제 표본 CSV 가 없으면 건너뛴다.
 */
@Tag("sql-golden")
class SqlGoldenReplayTest {

    static PostgreSQLContainer container;
    static SqlGoldenHarness harness;
    static GoldenFixtureSource.FixtureAvailability availability;

    @BeforeAll
    static void start() {
        GoldenFixtureSource source = GoldenFixtureSource.fromEnvironment();
        availability = source.inspect();
        // 지정한 표본 위치가 틀렸으면 컨테이너를 띄우기 전에 실패하고, 표본이 없으면(미지정) 컨테이너 없이 각 시험이 건너뛴다.
        if (availability.explicit()) {
            availability.requireOrSkip();
        }
        if (!availability.isComplete()) {
            return;
        }
        container = SqlGoldenHarness.newContainer();
        container.start();
        harness = new SqlGoldenHarness(container, source);
    }

    @AfterAll
    static void stop() throws Exception {
        if (harness != null) {
            harness.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    private static Map<String, RowSet> snapshot(DatabaseHandle db, long seasonId) throws Exception {
        Map<String, RowSet> tables = new LinkedHashMap<>();
        for (GoldenTable table : GoldenRowQueries.allForTurnGolden()) {
            tables.put(table.name(), harness.readRows(db, table.query(), seasonId));
        }
        return tables;
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void 같은_입력을_독립된_빈_DB_두_개에_적용하면_모든_테이블이_같다() throws Exception {
        availability.requireOrSkip();
        var bundle = harness.stageInputs();
        DatabaseHandle first = harness.createSqlDatabase("replay_a");
        DatabaseHandle second = harness.createSqlDatabase("replay_b");

        harness.execute(first, SqlGoldenScenario.turnGolden());
        harness.execute(second, SqlGoldenScenario.turnGolden());

        // 같은 입력 묶음(경로·해시)이 두 DB 에 쓰였고 DB 는 서로 다르다
        assertThat(harness.stageInputs().bundleSha256()).isEqualTo(bundle.bundleSha256());
        assertThat(first.name()).isNotEqualTo(second.name());
        long firstSeason = harness.practiceSeasonId(first);
        long secondSeason = harness.practiceSeasonId(second);
        Map<String, RowSet> a = snapshot(first, firstSeason);
        Map<String, RowSet> b = snapshot(second, secondSeason);
        List<String> report = new ArrayList<>();
        for (GoldenTable table : GoldenRowQueries.allForTurnGolden()) {
            var diff = RowSetDiff.compare(a.get(table.name()), b.get(table.name()), table.spec());
            if (!diff.isEmpty()) {
                report.add(diff.summary());
            }
        }
        assertThat(report).isEmpty();

        // 범위와 건수: 풀 전체 가격, 턴 스냅샷, 턴마다 대사 17행, 22(수동 대사)를 끼우지 않아 시도 번호는 모두 1
        RowSet prices = a.get("turn_price");
        long advanced = prices.rows().stream().filter(r -> (Long) r.get(prices.indexOf("turn_no")) >= 1).count();
        long turnZero = prices.size() - advanced;
        long pool = turnZero;
        assertThat(advanced).as("진행 턴 가격 행 수").isEqualTo(pool * SqlGoldenScenario.PATH_LENGTH);
        // 현재 표본: 풀 20종목 → 진행 턴 가격 200행 (P0-17 리뷰 13절)
        assertThat(pool).as("0턴 풀").isEqualTo(20);
        RowSet turns = a.get("turn");
        assertThat(turns.size()).as("턴 스냅샷 행 수").isEqualTo(SqlGoldenScenario.PATH_LENGTH);
        RowSet log = a.get("reconciliation_log");
        assertThat(log.size()).isEqualTo(17 * SqlGoldenScenario.PATH_LENGTH);
        assertThat(log.rows().stream().map(r -> r.get(log.indexOf("attempt_no"))).distinct().toList()).containsExactly(1L);
        // 경로는 1턴부터 마지막 턴까지 턴마다 한 달이다 (0턴은 시작 가격이라 경로에 없다)
        assertThat(a.get("season_path").size()).isEqualTo(SqlGoldenScenario.PATH_LENGTH);
        assertThat(a.get("season").get(0, "status")).isEqualTo("FINISHED");
        // 양쪽이 모두 비어 있어 헛으로 일치하지 않도록, 모든 표가 비어 있지 않고 구조 건수가 맞는지 확인한다
        for (GoldenTable table : GoldenRowQueries.allForTurnGolden()) {
            assertThat(a.get(table.name()).size()).as(table.name() + " 행 수(첫째 DB)").isPositive();
            assertThat(b.get(table.name()).size()).as(table.name() + " 행 수(둘째 DB)").isEqualTo(a.get(table.name()).size());
        }
        assertThat(a.get("season").size()).isEqualTo(1);
        assertThat(a.get("cash_account").size()).isEqualTo(1);
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void 끝난_뒤_20_21_23을_다시_실행해도_모든_테이블이_그대로다() throws Exception {
        availability.requireOrSkip();
        harness.stageInputs();
        DatabaseHandle db = harness.createSqlDatabase("idem");
        harness.execute(db, SqlGoldenScenario.turnGolden());
        long season = harness.practiceSeasonId(db);
        Map<String, RowSet> before = snapshot(db, season);

        harness.execute(db, SqlGoldenScenario.afterFinishIdempotence());

        assertThat(harness.practiceSeasonId(db)).isEqualTo(season);
        Map<String, RowSet> after = snapshot(db, season);
        for (GoldenTable table : GoldenRowQueries.allForTurnGolden()) {
            RowSetDiff.assertNoDifference(before.get(table.name()), after.get(table.name()), table.spec());
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void 재생_입력을_꺼내고_메모리_사본의_원장_한_행_변경을_잡는다() throws Exception {
        availability.requireOrSkip();
        harness.stageInputs();
        DatabaseHandle db = harness.createSqlDatabase("capture");
        harness.execute(db, SqlGoldenScenario.turnGolden());
        long season = harness.practiceSeasonId(db);

        SeasonReplayInput input = harness.captureReplayInput(db, season);

        assertThat(input.path().size()).isEqualTo(SqlGoldenScenario.PATH_LENGTH);
        // 경로는 턴 번호 1…PATH_LENGTH 오름차순으로 나온다 (재생 어댑터가 이 순서대로 공급한다)
        assertThat(input.path().rows().stream().map(r -> r.get(input.path().indexOf("turn_no"))).toList())
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, SqlGoldenScenario.PATH_LENGTH)
                        .boxed().map(Object.class::cast).toList());
        assertThat(input.actions().stream().filter(x -> x.kind() == ActionKind.ADVANCE_TURN).count())
                .isEqualTo(SqlGoldenScenario.PATH_LENGTH);
        assertThat(input.actions().stream().filter(x -> x.kind() == ActionKind.PLACE_ORDER).count()).isPositive();
        assertThat(input.actions().get(0).kind()).isEqualTo(ActionKind.PLACE_ORDER);
        assertThat(input.orderKeys()).hasSize((int) input.actions().stream().filter(x -> x.kind() == ActionKind.PLACE_ORDER).count());
        assertThat(input.toString()).doesNotContain("KR").doesNotContain("US");

        GoldenTable ledger = GoldenRowQueries.cashLedger();
        RowSet original = harness.readRows(db, ledger.query(), season);
        // 재생 입력의 행동 키와 비교 질의의 주문 키가 같은 모양이다 (키 정의는 GoldenRowQueries 한 곳)
        RowSet orderRows = harness.readRows(db, GoldenRowQueries.orders().query(), season);
        assertThat(orderRows.size()).isEqualTo(input.orderKeys().size());
        assertThat(original.rows().stream().map(r -> (String) r.get(original.indexOf("order_key")))
                .filter(k -> !k.equals("-")).distinct().toList())
                .isNotEmpty().allSatisfy(k -> assertThat(input.orderKeys().values()).contains(k));
        RowSet mutated = original.copy();
        BigDecimal amount = (BigDecimal) mutated.get(1, "amount");
        mutated.set(1, "amount", amount.add(new BigDecimal("0.0001")));
        var diff = RowSetDiff.compare(original, mutated, ledger.spec());
        assertThat(diff.entries()).hasSize(1);
        assertThat(diff.count(Kind.CHANGED)).isEqualTo(1);
        assertThat(diff.entries().get(0).changedColumns()).containsExactly("amount");
        // 행을 하나 빼면 누락 한 건
        RowSet dropped = original.copy();
        dropped.removeRow(0);
        assertThat(RowSetDiff.compare(original, dropped, ledger.spec()).count(Kind.REMOVED)).isEqualTo(1);
    }
}
