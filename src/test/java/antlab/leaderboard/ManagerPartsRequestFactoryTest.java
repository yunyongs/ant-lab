package antlab.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.engine.client.EngineManagerPartsRequest;
import antlab.engine.client.EngineManagerPartsResult;
import antlab.engine.client.EngineRefusedException;
import antlab.member.MemberKind;
import antlab.scoring.RatingExclusion;
import antlab.scoring.SeasonReportSources.SeasonFacts;
import antlab.season.SeasonStatus;
import antlab.support.FakeEngineServer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pure steps of the parts service: which seasons need no engine call, the request built from stored facts (equal to
 * the shared contract fixture), the answer checks before a row is stored, the row mapping and the run refusals. No Spring.
 */
class ManagerPartsRequestFactoryTest {

    private static final JsonNode FIXTURE = JsonMapper.shared().readTree(FakeEngineServer.fixture("manager-parts-request.json"));

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private static List<BigDecimal> decimals(JsonNode array) {
        return strings(array).stream().map(BigDecimal::new).toList();
    }

    /** The basis of the fixture's season, typed as the reader would produce it from stored rows. */
    private static ManagerPartsBasis fixtureBasis(BigDecimal riskScore, RatingExclusion exclusion, int pathLength) {
        JsonNode team = FIXTURE.get("team");
        SeasonFacts facts = new SeasonFacts(SeasonStatus.FINISHED, pathLength, pathLength, new BigDecimal("100000000.0000"),
                new BigDecimal("-0.20000000"), new BigDecimal("0.00015000"), new BigDecimal("0.00250000"),
                new BigDecimal("0.00400000"), 10, 30, 30, 30);
        List<LocalDate> months = strings(FIXTURE.get("months")).stream().map(LocalDate::parse).toList();
        return new ManagerPartsBasis(7L, 3L, MemberKind.HUMAN, LocalDate.parse("2026-01-02"), facts, months,
                decimals(team.get("totals")), decimals(team.get("returns")), decimals(team.get("benchmarkReturns")),
                new ManagerPartsBasis.Score(new BigDecimal("0.31250000"), new BigDecimal("0.25000000"), riskScore,
                        new BigDecimal("61.50"), "s63-v3", exclusion));
    }

    private static EngineManagerPartsResult answer(String fixture) {
        return JsonMapper.shared().readValue(FakeEngineServer.fixture(fixture), EngineManagerPartsResult.class);
    }

    @Test
    void theRequestIsTheStoredFactsAsPlainStrings() {
        EngineManagerPartsRequest request = ManagerPartsRequestFactory.request(fixtureBasis(new BigDecimal("87.50"), null, 12));

        assertThat(request.referenceVersion()).isEqualTo(FIXTURE.get("referenceVersion").asString());
        assertThat(request.baseDate()).hasToString(FIXTURE.get("baseDate").asString());
        assertThat(request.months().stream().map(LocalDate::toString).toList()).isEqualTo(strings(FIXTURE.get("months")));
        JsonNode settings = FIXTURE.get("settings");
        assertThat(request.settings().pathLength()).isEqualTo(settings.get("pathLength").asInt());
        assertThat(request.settings().initialCash()).isEqualTo(settings.get("initialCash").asString());
        assertThat(request.settings().feeRateKr()).isEqualTo(settings.get("feeRateKr").asString());
        assertThat(request.settings().feeRateUs()).isEqualTo(settings.get("feeRateUs").asString());
        assertThat(request.settings().fxSpreadRate()).isEqualTo(settings.get("fxSpreadRate").asString());
        assertThat(request.settings().riskLimit()).isEqualTo(settings.get("riskLimit").asString());
        assertThat(request.settings().startFormation()).isEqualTo(new EngineManagerPartsRequest.StartFormation(10, 30, 30, 30));
        assertThat(request.settings().memberKind()).isEqualTo(MemberKind.HUMAN);
        JsonNode team = FIXTURE.get("team");
        assertThat(request.team().totals()).isEqualTo(strings(team.get("totals")));
        // returns go out unrounded: the 30-place values of the fixture come back digit for digit
        assertThat(request.team().returns()).isEqualTo(strings(team.get("returns")));
        assertThat(request.team().benchmarkReturns()).isEqualTo(strings(team.get("benchmarkReturns")));
        assertThat(request.team().sharpe()).isEqualTo(team.get("sharpe").asString());
        assertThat(request.team().benchSharpe()).isEqualTo(team.get("benchSharpe").asString());
        assertThat(request.team().riskScore()).isEqualTo(team.get("riskScore").asString());
    }

    @Test
    void aSeasonWithoutRiskItemSendsNull() {
        assertThat(ManagerPartsRequestFactory.request(fixtureBasis(null, null, 12)).team().riskScore()).isNull();
    }

    @Test
    void aStoredValueThatDoesNotFitTheWireIsAJavaFault() {
        ManagerPartsBasis basis = fixtureBasis(null, null, 12);
        List<BigDecimal> totals = new ArrayList<>(basis.totals());
        totals.set(3, new BigDecimal("99000000.5000"));
        ManagerPartsBasis halfWon = new ManagerPartsBasis(7L, 3L, MemberKind.HUMAN, basis.baseDate(), basis.settings(),
                basis.months(), totals, basis.returns(), basis.benchmarkReturns(), basis.score());
        assertThatThrownBy(() -> ManagerPartsRequestFactory.request(halfWon)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void knownExclusionsNeedNoEngineCall() {
        assertThat(ManagerPartsRequestFactory.knownExclusion(RatingExclusion.NOT_STARTED, 12))
                .isEqualTo(ManagerPartsExclusion.NOT_STARTED);
        assertThat(ManagerPartsRequestFactory.knownExclusion(null, 120)).isEqualTo(ManagerPartsExclusion.NO_REFERENCE);
        assertThat(ManagerPartsRequestFactory.knownExclusion(RatingExclusion.NOT_STARTED, 120))
                .isEqualTo(ManagerPartsExclusion.NOT_STARTED);
        for (int length : new int[] {12, 24, 60}) {
            assertThat(ManagerPartsRequestFactory.knownExclusion(null, length)).as("length " + length).isNull();
            // a season without a path distribution still gets S: S does not use the season score's result part
            assertThat(ManagerPartsRequestFactory.knownExclusion(RatingExclusion.NO_SCORE, length)).isNull();
        }
    }

    @Test
    void refusalsMapToTheTwoStoredReasons() {
        assertThat(ManagerPartsService.exclusionOf(EngineRefusedException.Reason.REFERENCE_MISMATCH))
                .isEqualTo(ManagerPartsExclusion.REFERENCE_MISMATCH);
        assertThat(ManagerPartsService.exclusionOf(EngineRefusedException.Reason.FEED_INPUT))
                .isEqualTo(ManagerPartsExclusion.NO_REFERENCE);
        assertThat(ManagerPartsService.exclusionOf(EngineRefusedException.Reason.NO_REFERENCE))
                .isEqualTo(ManagerPartsExclusion.NO_REFERENCE);
    }

    @Test
    void theRowStoresTheAnswerAsSentAndTheJavaPartsBeside() {
        ManagerPartsBasis basis = fixtureBasis(new BigDecimal("87.50"), null, 12);
        SeasonManagerParts row = ManagerPartsRequestFactory.ranked(basis, answer("manager-parts-response.json"));

        assertThat(row.exclusion()).isNull();
        assertThat(row.referenceVersion()).isEqualTo("ref69-v1");
        assertThat(row.ranked().referenceKey()).isEqualTo("3f".repeat(32));
        assertThat(row.ranked().calmarDiff()).isEqualTo(new BigDecimal("-999998.476190466190"));
        assertThat(row.ranked().benchMdd()).isEqualTo(new BigDecimal("0.000000000000"));
        assertThat(row.ranked().pctCalmarDiff()).isEqualTo(new BigDecimal("0.25000000"));
        assertThat(row.ranked().partRiskAdjusted()).isEqualTo(new BigDecimal("41.00000000"));
        assertThat(row.partProcess()).isEqualTo(new BigDecimal("61.50"));
        assertThat(row.processScoreVersion()).isEqualTo("s63-v3");
        // 108,000,000 / 100,000,000
        assertThat(row.finalMultiple()).isEqualTo(new BigDecimal("1.08000000"));

        SeasonManagerParts excluded = ManagerPartsRequestFactory.excluded(basis, ManagerPartsExclusion.REFERENCE_MISMATCH);
        assertThat(excluded.ranked()).isNull();
        assertThat(excluded.exclusion()).isEqualTo(ManagerPartsExclusion.REFERENCE_MISMATCH);
        assertThat(excluded.finalMultiple()).isEqualTo(new BigDecimal("1.08000000"));
    }

    @Test
    void anAnswerIsCheckedBeforeItIsStored() {
        EngineManagerPartsRequest withRisk = ManagerPartsRequestFactory.request(fixtureBasis(new BigDecimal("87.50"), null, 12));
        EngineManagerPartsRequest noRisk = ManagerPartsRequestFactory.request(fixtureBasis(null, null, 12));
        EngineManagerPartsResult good = answer("manager-parts-response.json");
        EngineManagerPartsResult goodNoRisk = answer("manager-parts-response-no-risk-item.json");

        assertThat(ManagerPartsService.answerProblem(withRisk, good)).isNull();
        assertThat(ManagerPartsService.answerProblem(noRisk, goodNoRisk)).isNull();
        assertThat(ManagerPartsService.answerProblem(noRisk, good)).isEqualTo("risk_item");
        assertThat(ManagerPartsService.answerProblem(withRisk, goodNoRisk)).isEqualTo("risk_item");
        assertThat(ManagerPartsService.answerProblem(withRisk, with(good, 199, good.botVersion(), good.engineVersion())))
                .isEqualTo("reference_size");
        assertThat(ManagerPartsService.answerProblem(withRisk, with(good, 200, good.botVersion(), "0.1.0-" + "x".repeat(15))))
                .isEqualTo("engine_version");
        assertThat(ManagerPartsService.answerProblem(withRisk, with(good, 200, good.botVersion(), " 0.1.0")))
                .isEqualTo("engine_version");
        assertThat(ManagerPartsService.answerProblem(withRisk, with(good, 200, good.botVersion(), " ")))
                .isEqualTo("engine_version");
        assertThat(ManagerPartsService.answerProblem(withRisk, with(good, 200, "abcdefghijklmnopqrstu/1", good.engineVersion())))
                .isEqualTo("bot_version");
        assertThat(ManagerPartsService.answerProblem(withRisk, with(good, 200, "abcdefghijklmnopqr/1", "x".repeat(20))))
                .as("20 characters fit the columns").isNull();
    }

    private static EngineManagerPartsResult with(EngineManagerPartsResult a, int size, String bot, String engine) {
        return new EngineManagerPartsResult(a.referenceVersion(), a.referenceKey(), size, bot, engine, a.components(),
                a.percentiles(), a.parts());
    }

    @Test
    void aSingleAssetLimitOrAShortTimeoutRefusesEveryRun() {
        assertThat(ManagerPartsService.refusal(Duration.ofSeconds(60), null)).isNull();
        assertThat(ManagerPartsService.refusal(Duration.ofSeconds(30), null)).isNull();
        assertThat(ManagerPartsService.refusal(Duration.ofMillis(29_999), null)).isEqualTo("engine_timeout_below_minimum");
        assertThat(ManagerPartsService.refusal(Duration.ofSeconds(60), new BigDecimal("0.40"))).isEqualTo("single_asset_limit");
    }

    @Test
    void theBackoffDoublesFromTwoDelaysAndStopsAtTheCap() {
        ManagerPartsJob job = new ManagerPartsJob(null, null,
                new ManagerPartsJobProperties(false, Duration.ofSeconds(60), Duration.ofMinutes(10)));
        assertThat(job.wait(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(job.wait(2)).isEqualTo(Duration.ofMinutes(4));
        assertThat(job.wait(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(job.wait(4)).isEqualTo(Duration.ofMinutes(10));
        assertThat(job.wait(40)).isEqualTo(Duration.ofMinutes(10));
    }
}
