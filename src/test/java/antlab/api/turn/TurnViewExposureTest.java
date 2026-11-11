package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getHoldings;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.HoldingDto;
import antlab.account.HoldingsDto;
import antlab.history.ReturnKind;
import antlab.season.FormationDto;
import antlab.season.TurnResultDto;
import antlab.support.IntegrationTest;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 턴 조회·보유 조회의 노출 상한 — 응답 키는 record 구성 요소(중첩 포함)와 같고, 턴별 수익률 종류·경로 달의 연월·월 블록·시드·엔진 버전이
 * 본문(성공·오류)과 로그에 없으며, 현재 턴 이후의 값이 나가지 않는다. 정적 검사(원천 열 태그)는 {@code ExposureContractTest} 가 두 응답 record 를 덮는다.
 */
@ExtendWith(OutputCaptureExtension.class)
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnViewExposureTest {

    static final Set<String> FORBIDDEN_KEYS = Set.of("seed", "engineVersion", "dataSnapshotDate", "baseDate",
            "monthBlockId", "monthBlock", "monthStart", "memberId", "version", "createdAt", "updatedAt", "attemptNo",
            "attempt", "checkCode", "mismatchCount", "reconciliation", "results", "path", "returnKind", "localReturn",
            "fxChange", "month", "year", "date");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private static Set<String> components(Class<?> record) {
        Set<String> out = new HashSet<>();
        Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).forEach(out::add);
        return out;
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> out = new HashSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static void allKeys(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                out.add(e.getKey());
                allKeys(e.getValue(), out);
            });
        } else if (node.isArray()) {
            node.forEach(n -> allKeys(n, out));
        }
    }

    @Test
    void 응답_키는_record_구성_요소와_같고_금지_값이_본문과_로그에_없다(CapturedOutput output) throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        String seed = jdbc.queryForObject("SELECT seed::text FROM season WHERE id = ?", String.class, season);
        String engine = jdbc.queryForObject("SELECT engine_version FROM season WHERE id = ?", String.class, season);
        for (int t = 0; t < 3; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }

        MockHttpServletResponse turnResponse = getTurn(mvc, season, 2);
        MockHttpServletResponse holdingsResponse = getHoldings(mvc, season);
        JsonNode turn = json(turnResponse);
        JsonNode holdings = json(holdingsResponse);

        assertThat(keys(turn)).isEqualTo(components(TurnResultDto.class));
        assertThat(keys(turn.get("targetFormation"))).isEqualTo(components(FormationDto.class));
        assertThat(keys(holdings)).isEqualTo(components(HoldingsDto.class));
        assertThat(keys(holdings.get("currentFormation"))).isEqualTo(components(FormationDto.class));
        assertThat(holdings.get("holdings")).isNotEmpty();
        holdings.get("holdings").forEach(h -> assertThat(keys(h)).isEqualTo(components(HoldingDto.class)));

        Set<String> all = new HashSet<>();
        allKeys(turn, all);
        allKeys(holdings, all);
        assertThat(all).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);

        // 오류 응답에도 같다
        MockHttpServletResponse error = getTurn(mvc, season, 4);
        assertThat(error.getStatus()).isEqualTo(409);
        json(error).propertyNames().forEach(k -> assertThat(FORBIDDEN_KEYS).doesNotContain(k));

        List<String> bodies = new ArrayList<>(List.of(turnResponse.getContentAsString(),
                holdingsResponse.getContentAsString(), error.getContentAsString()));
        List<String> months = jdbc.queryForList("SELECT mb.month_start::text FROM season_path sp"
                + " JOIN month_block mb ON mb.id = sp.month_block_id WHERE sp.season_id = ? ORDER BY sp.turn_no",
                String.class, season);
        assertThat(months).isNotEmpty();
        for (String body : bodies) {
            assertThat(body).doesNotContain(seed).doesNotContain(engine).doesNotContainPattern("\\d{4}-\\d{2}");
            for (ReturnKind kind : ReturnKind.values()) {
                assertThat(body).as("수익률 종류").doesNotContain(kind.name());
            }
        }
        // 호출 뒤에도 경로 달·수익률 종류·엔진 버전이 로그에 없다
        months.forEach(m -> assertThat(output.getAll()).as("로그의 달").doesNotContain(m));
        for (ReturnKind kind : ReturnKind.values()) {
            assertThat(output.getAll()).as("로그의 수익률 종류").doesNotContain(kind.name());
        }
        assertThat(output.getAll()).doesNotContain(engine);
    }

    @Test
    void 현재_턴_이후의_가격과_턴은_나가지_않는다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 2; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        // 위반 주입: 다음 턴(3) 가격 행을 가격 2배로 미리 만든다. 조회가 현재 턴(2) 행만 읽어야 시험이 통과한다.
        assertThat(jdbc.queryForObject("SELECT max(turn_no) FROM turn_price WHERE season_id = ?", Integer.class, season))
                .isEqualTo(2);
        String turn2Before = getTurn(mvc, season, 2).getContentAsString();
        int injected = jdbc.update("INSERT INTO turn_price (season_id, turn_no, asset_id, price)"
                + " SELECT season_id, turn_no + 1, asset_id, price * 2 FROM turn_price WHERE season_id = ? AND turn_no = 2",
                season);
        assertThat(injected).as("주입한 미래 가격 행").isPositive();
        JsonNode holdings = json(getHoldings(mvc, season));
        assertThat(holdings.get("turnNo").asInt()).isEqualTo(2);
        for (JsonNode h : holdings.get("holdings")) {
            String stored = jdbc.queryForObject("SELECT price::numeric(19,0)::text FROM turn_price WHERE season_id = ?"
                    + " AND turn_no = 2 AND asset_id = ?", String.class, season, h.get("assetId").asLong());
            assertThat(h.get("price").asString()).as("현재 턴 가격(주입한 다음 턴 행이 아니다)").isEqualTo(stored);
        }
        assertThat(json(getTurn(mvc, season, 2))).as("미래 행 주입 전후 턴 2 본문")
                .isEqualTo(JsonMapper.shared().readTree(turn2Before));
        assertThat(getTurn(mvc, season, 3).getStatus()).isEqualTo(409);
    }
}
