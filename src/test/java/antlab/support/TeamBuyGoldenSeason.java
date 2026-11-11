package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.MonthBlockRepository;
import antlab.support.SeasonReplayInput.ActionKind;
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
        PostgreSQLContainer container = SqlGoldenHarness.newContainer();
        container.start();
        SqlGoldenHarness harness = new SqlGoldenHarness(container, SOURCE);
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
        harness.execute(apiDb, SqlGoldenScenario.apiDbLoad());
        DatabaseHandle sqlDb = harness.createSqlDatabase("team_buy_sql");
        harness.execute(sqlDb, SqlGoldenScenario.teamBuyGolden());
        long sqlSeason = harness.practiceSeasonId(sqlDb);

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
        long apiSeason = harness.practiceSeasonId(apiDb);

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
        for (ReplayAction action : input.actions()) {
            if (action.kind() != ActionKind.PLACE_ORDER) {
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

    @Override
    public void close() throws Exception {
        harness.close();
        container.stop();
    }
}
