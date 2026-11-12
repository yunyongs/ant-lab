package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.support.SeasonReplayInput.AdvanceTurn;
import antlab.support.SeasonReplayInput.FormationChange;
import antlab.support.SeasonReplayInput.LineupChange;
import antlab.support.SeasonReplayInput.PlaceOrder;
import antlab.support.SeasonReplayInput.ReplayAction;
import antlab.support.SqlGoldenHarness.DatabaseHandle;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 팀 구성 골든 문맥(P0-31 골든과 같은 준비): 하네스 컨테이너에 SQL 기준 DB(db/20 → 21)와 API DB(Flyway + 표본 적재)를 만들고,
 * SQL 이 만든 0턴 주문을 같은 순서로 API 에 재생해 API 시즌을 만든다. 대사·턴 전환 골든이 같은 시작점으로 쓴다.
 * 컨테이너 수명은 이 객체가 가진다({@link #close()}).
 */
public final class TeamBuyGoldenSeason implements AutoCloseable {

    public static final long GOLDEN_SEED = 14L;
    public static final GoldenFixtureSource SOURCE = GoldenFixtureSource.fromEnvironment();

    /** 재생이 끝난 뒤의 시즌 번호들. */
    public record Replayed(DatabaseHandle sqlDb, long sqlSeason, long apiSeason, int orders) {
    }

    private final PostgreSQLContainer container;
    private final SqlGoldenHarness harness;
    private final DatabaseHandle apiDb;

    private TeamBuyGoldenSeason(PostgreSQLContainer container, SqlGoldenHarness harness, DatabaseHandle apiDb) {
        this.container = container;
        this.harness = harness;
        this.apiDb = apiDb;
    }

    /** 표본이 없으면 false(건너뜀), 지정했는데 파일이 없으면 AssertionError 로 실패한다. */
    public static boolean sampleAvailable() {
        GoldenFixtureSource.FixtureAvailability availability = SOURCE.inspect();
        if (availability.explicit() && !availability.isComplete()) {
            availability.requireOrSkip();
        }
        if (!availability.isComplete()) {
            System.out.println("[sql-golden] 건너뜀 — " + availability.skipMessage());
        }
        return availability.isComplete();
    }

    /** 컨테이너와 두 DB 를 만들고 앱이 API DB 를 보도록 접속 정보를 등록한다(@DynamicPropertySource 에서 부른다). */
    public static TeamBuyGoldenSeason start(DynamicPropertyRegistry registry, String apiDbName) throws Exception {
        return start(registry, apiDbName, SOURCE);
    }

    /** 롤백 드릴 파일까지 컨테이너에 놓는 판(드릴 골든 전용). */
    public static TeamBuyGoldenSeason startWithDrill(DynamicPropertyRegistry registry, String apiDbName) throws Exception {
        return start(registry, apiDbName, SOURCE.withDrill());
    }

    private static TeamBuyGoldenSeason start(DynamicPropertyRegistry registry, String apiDbName, GoldenFixtureSource source)
            throws Exception {
        PostgreSQLContainer container = SqlGoldenHarness.newContainer();
        container.start();
        SqlGoldenHarness harness = new SqlGoldenHarness(container, source);
        harness.stageInputs();
        DatabaseHandle apiDb = harness.createSqlDatabase(apiDbName);
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(5432) + "/" + apiDb.name());
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
        return new TeamBuyGoldenSeason(container, harness, apiDb);
    }

    public SqlGoldenHarness harness() {
        return harness;
    }

    public DatabaseHandle apiDb() {
        return apiDb;
    }

    /**
     * API DB 에 표본을 적재하고(10 → 12), SQL 기준 DB 에서 시즌 시작 → 0턴 팀 매수 → 검증을 돌린 뒤,
     * 같은 경로·시드·설정으로 API 시즌을 시작하고 SQL 의 0턴 주문을 같은 순서·수량·선언 포지션으로 보낸다.
     */
    public Replayed replay(MockMvcTester mvc, DeferredPathSource deferred, MonthBlockRepository monthBlocks)
            throws Exception {
        return replay(mvc, deferred, monthBlocks, SqlGoldenScenario.teamBuyGolden(), "team_buy_sql");
    }

    /**
     * 기준 SQL 계획을 고를 수 있는 판. 계획은 0턴 팀 매수까지 같은 시작을 가져야 한다(예: 턴 골든 {@code TURN_GOLDEN} 은
     * 20 → 21 뒤 23 을 10번 더 돌린다). 재생하는 주문은 0턴 주문뿐이다.
     */
    public Replayed replay(MockMvcTester mvc, DeferredPathSource deferred, MonthBlockRepository monthBlocks,
            SqlGoldenScenario.ScenarioPlan sqlPlan, String sqlDbName) throws Exception {
        harness.execute(apiDb, SqlGoldenScenario.apiDbLoad());
        DatabaseHandle sqlDb = harness.createSqlDatabase(sqlDbName);
        harness.execute(sqlDb, sqlPlan);
        long sqlSeason = harness.practiceSeasonId(sqlDb);

        long apiSeason = startApiSeason(mvc, deferred, monthBlocks, sqlDb, sqlSeason);

        SeasonReplayInput input = harness.captureReplayInput(sqlDb, sqlSeason);
        RowSet lineup = harness.readRows(sqlDb, GoldenRowQueries.lineup().query(), sqlSeason);
        Map<String, String> declared = new HashMap<>();
        for (int i = 0; i < lineup.size(); i++) {
            assertThat(lineup.get(i, "start_turn")).isEqualTo(0L);
            declared.put(lineup.get(i, "market") + "/" + lineup.get(i, "ticker"),
                    (String) lineup.get(i, "declared_position"));
        }
        Map<String, Long> assetIds = new HashMap<>();
        JsonNode assets = JsonMapper.shared().readTree(
                mvc.get().uri("/api/v1/pool").exchange().getResponse().getContentAsString()).get("assets");
        assets.forEach(a -> assetIds.put(a.get("market").asString() + "/" + a.get("ticker").asString(),
                a.get("assetId").asLong()));

        int count = 0;
        for (ReplayAction replay : input.actions()) {
            if (!(replay instanceof PlaceOrder action)) {
                continue;
            }
            String[] k = action.key().split("/");
            assertThat(k[0]).as("팀 구성은 0턴 주문뿐이다").isEqualTo("0");
            assertThat(k[3]).isEqualTo("BUY");
            String asset = k[1] + "/" + k[2];
            String body = "{\"expectedTurnNo\": 0, \"side\": \"BUY\", \"assetId\": " + assetIds.get(asset)
                    + ", \"quantity\": " + action.quantity() + ", \"declaredPosition\": \"" + declared.get(asset) + "\"}";
            MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + apiSeason + "/orders")
                    .contentType(MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
            assertThat(r.getStatus()).as("재생 주문 " + count).isEqualTo(201);
            count++;
        }
        return new Replayed(sqlDb, sqlSeason, apiSeason, count);
    }

    /** 라인업 변경을 내부 서비스로 부르는 쪽(시험이 서비스를 주입해 넘긴다 — 공개 HTTP 경로가 없다). */
    @FunctionalInterface
    public interface LineupChangeRunner {
        void change(long apiSeason, int expectedTurnNo, long assetId, String position);
    }

    /**
     * 팀 구성 뒤의 행동까지 모두 재생하는 판(P0-36 매매 → P0-37 전술·라인업 변경). SQL 기준 DB 에서 {@code sqlPlan} 을 돌린 뒤
     * 같은 경로·시드·설정으로 API 시즌을 시작하고, 캡처한 행동을 순서대로 보낸다: 0턴 매수 → 턴 전환 → 턴 t 의 주문(BUY·SELL)과
     * 전술 변경(POST, 시각 순) → 그 턴의 라인업 변경(내부 서비스) → …
     * BUY 의 선언 포지션은 SQL 라인업에서 (종목, 시작 턴 = 주문 턴) 행의 선언이 하나면 그 값(같은 선언의 구간이 둘 이상이어도 한 값), 0개면 생략(열린 구간에 추가 매수),
     * 서로 다른 선언이 둘 이상이면 실패한다(라인업 변경으로 연 구간과 구분할 수 없는 확장 시나리오는 행동 목록을 따로 만든다). SELL 은 선언을 보내지 않는다.
     *
     * @return 재생한 주문 수
     */
    public Replayed replayTrades(MockMvcTester mvc, DeferredPathSource deferred, MonthBlockRepository monthBlocks,
            SqlGoldenScenario.ScenarioPlan sqlPlan, String sqlDbName, LineupChangeRunner lineupRunner) throws Exception {
        harness.execute(apiDb, SqlGoldenScenario.apiDbLoad());
        DatabaseHandle sqlDb = harness.createSqlDatabase(sqlDbName);
        harness.execute(sqlDb, sqlPlan);
        long sqlSeason = harness.practiceSeasonId(sqlDb);
        long apiSeason = startApiSeason(mvc, deferred, monthBlocks, sqlDb, sqlSeason);

        SeasonReplayInput input = harness.captureReplayInput(sqlDb, sqlSeason);
        RowSet lineup = harness.readRows(sqlDb, GoldenRowQueries.lineup().query(), sqlSeason);
        Map<String, Long> assetIds = new HashMap<>();
        JsonNode assets = JsonMapper.shared().readTree(
                mvc.get().uri("/api/v1/pool").exchange().getResponse().getContentAsString()).get("assets");
        assets.forEach(a -> assetIds.put(a.get("market").asString() + "/" + a.get("ticker").asString(),
                a.get("assetId").asLong()));

        int count = 0;
        for (ReplayAction replay : input.actions()) {
            switch (replay) {
                case AdvanceTurn advance -> {
                    MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + apiSeason + "/turns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedTurnNo\": " + (advance.toTurn() - 1) + "}").exchange().getResponse();
                    assertThat(r.getStatus()).as("재생 전환 " + advance.toTurn()).isEqualTo(201);
                }
                case FormationChange change -> {
                    String reason = change.reason() == null ? "" : ", \"reason\": "
                            + JsonMapper.shared().writeValueAsString(change.reason());
                    String body = "{\"expectedTurnNo\": " + change.turn() + ", \"formation\": {\"goalkeeper\": "
                            + change.after().goalkeeper() + ", \"defender\": " + change.after().defender()
                            + ", \"midfielder\": " + change.after().midfielder() + ", \"forward\": "
                            + change.after().forward() + "}" + reason + "}";
                    MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + apiSeason + "/formation-changes")
                            .contentType(MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
                    assertThat(r.getStatus()).as("재생 전술 변경 " + change.turn() + "/" + change.ordinal()).isEqualTo(201);
                }
                case LineupChange change -> lineupRunner.change(apiSeason, (int) change.turn(),
                        assetIds.get(change.asset()), change.position());
                case PlaceOrder action -> {
                    String[] k = action.key().split("/");
                    long turn = Long.parseLong(k[0]);
                    String asset = k[1] + "/" + k[2];
                    String side = k[3];
                    String declared = null;
                    if (side.equals("BUY")) {
                        List<String> starts = new ArrayList<>();
                        int lengthful = 0;
                        for (int i = 0; i < lineup.size(); i++) {
                            if ((lineup.get(i, "market") + "/" + lineup.get(i, "ticker")).equals(asset)
                                    && lineup.get(i, "start_turn").equals(turn)) {
                                starts.add((String) lineup.get(i, "declared_position"));
                                // 길이 없는 구간(end_turn = start_turn)이 아닌 구간
                                if (!sameTurn(lineup.get(i, "end_turn"), turn)) {
                                    lengthful++;
                                }
                            }
                        }
                        // 같은 턴에 매수→전량 매도→재매수면 같은 선언의 구간이 둘 열린다. 이 드릴이 유일한 사례이며, 선언은 하나이고
                        // 하나를 뺀 나머지는 모두 길이 없는 구간이어야 한다(같은 선언의 열린 구간이 둘 생기는 결함을 놓치지 않는다)
                        assertThat(starts.stream().distinct().count()).as("같은 종목·같은 턴에 새로 연 구간의 선언은 많아야 하나다")
                                .isLessThanOrEqualTo(1);
                        assertThat(lengthful).as("같은 종목·같은 턴에 연 구간 중 길이가 있는 구간은 많아야 하나다").isLessThanOrEqualTo(1);
                        declared = starts.isEmpty() ? null : starts.get(0);
                    }
                    String body = "{\"expectedTurnNo\": " + turn + ", \"side\": \"" + side + "\", \"assetId\": "
                            + assetIds.get(asset) + ", \"quantity\": " + action.quantity()
                            + (declared == null ? "" : ", \"declaredPosition\": \"" + declared + "\"") + "}";
                    MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + apiSeason + "/orders")
                            .contentType(MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
                    assertThat(r.getStatus()).as("재생 주문 " + count).isEqualTo(201);
                    count++;
                }
            }
        }
        return new Replayed(sqlDb, sqlSeason, apiSeason, count);
    }

    private static boolean sameTurn(Object endTurn, long turn) {
        return endTurn != null && endTurn.equals(turn);
    }

    /** SQL 시즌의 경로·시드·설정으로 API 시즌을 시작하고 번호를 돌려준다(두 재생이 함께 쓴다). */
    private long startApiSeason(MockMvcTester mvc, DeferredPathSource deferred, MonthBlockRepository monthBlocks,
            DatabaseHandle sqlDb, long sqlSeason) throws Exception {
        RowSet path = harness.readRows(sqlDb, GoldenRowQueries.seasonPath().query(), sqlSeason);
        List<LocalDate> months = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            months.add((LocalDate) path.get(i, "month_start"));
        }
        deferred.set(new FixedPathSource("p0-sql", months, monthBlocks));
        MockHttpServletResponse started = mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": " + SqlGoldenScenario.PATH_LENGTH + "}")
                .exchange().getResponse();
        assertThat(started.getStatus()).isEqualTo(201);
        return harness.practiceSeasonId(apiDb);
    }

    @Override
    public void close() throws Exception {
        GoldenCleanup.closeThenStop(harness, container);
    }
}
