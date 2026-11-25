package antlab.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import antlab.agent.detection.DetectionImportService;
import antlab.agent.detection.DetectionStatus;
import antlab.common.money.MoneyMath;
import antlab.member.MemberKind;
import antlab.support.DetectionFixtures;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * The leaderboard over rows written straight into the tables: buckets, the minimum, ranking, the formula as data, agent
 * rows and the viewer's own block. The viewer is the default test member.
 */
class LeaderboardServiceTest extends LeaderboardTestBase {

    @Autowired
    LeaderboardService leaderboard;
    @Autowired
    DetectionImportService detections;

    private ManagerLeaderboardDto board(LeaderboardKind kind) {
        return leaderboard.get(kind);
    }

    private static ManagerLeaderboardDto.Row row(ManagerLeaderboardDto board, String name) {
        return board.rows().stream().filter(r -> r.displayName().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> names(ManagerLeaderboardDto board) {
        return board.rows().stream().map(ManagerLeaderboardDto.Row::displayName).toList();
    }

    private void seasons(long member, String... flats) {
        for (String f : flats) {
            counted(member, P.flat(f));
        }
    }

    private void agentSeasons(long agent, int n, String flat) {
        for (int i = 0; i < n; i++) {
            countedAgent(agent, P.flat(flat), 1, null);
        }
    }

    @Test
    void 걸러_보기는_사람과_에이전트를_나누고_순위는_그_안에서_매긴다() {
        long human = human("lb-human");
        seasons(human, "50", "50", "50");
        long agent = agent(human, "Calm", "fake/lb-a");
        agentSeasons(agent, 5, "70");

        assertThat(names(board(LeaderboardKind.ALL))).containsExactly("Calm · fake/lb-a", "lb-human");
        assertThat(row(board(LeaderboardKind.ALL), "lb-human").rank()).isEqualTo(2);
        ManagerLeaderboardDto humans = board(LeaderboardKind.HUMAN);
        assertThat(names(humans)).containsExactly("lb-human");
        assertThat(humans.rows().get(0).rank()).isEqualTo(1);
        assertThat(humans.kind()).isEqualTo(LeaderboardKind.HUMAN);
        assertThat(names(board(LeaderboardKind.AGENT))).containsExactly("Calm · fake/lb-a");
    }

    @Test
    void 최소_시즌_미만은_표에_없고_unranked에_센다() {
        long two = human("lb-two");
        seasons(two, "60", "60");
        long agent = agent(two, "Bold", "fake/lb-b");
        agentSeasons(agent, 4, "60");
        long zero = human("lb-zero");
        season(zero, "IN_PROGRESS");

        ManagerLeaderboardDto all = board(LeaderboardKind.ALL);
        assertThat(all.rows()).isEmpty();
        assertThat(all.unranked()).isEqualTo(new ManagerLeaderboardDto.Unranked(1, 1));
        assertThat(board(LeaderboardKind.HUMAN).unranked()).isEqualTo(new ManagerLeaderboardDto.Unranked(1, 1));

        countedAgent(agent, P.flat("60"), 1, null);
        ManagerLeaderboardDto after = board(LeaderboardKind.ALL);
        assertThat(names(after)).containsExactly("Bold · fake/lb-b");
        assertThat(after.unranked()).isEqualTo(new ManagerLeaderboardDto.Unranked(1, 0));
    }

    @Test
    void 각_시즌은_한_칸에만_들고_칸의_합은_시즌_수와_같다() {
        long h = human("lb-buckets");
        seasons(h, "40", "50", "60");
        season(h, "VOID");
        season(h, "IN_PROGRESS");
        season(h, "PAUSED");
        season(h, "HALTED");
        season(h, "ABANDONING");
        long pending = season(h, "FINISHED");
        season(h, "FINISHED");
        excluded(h, "NOT_STARTED");
        excluded(h, "REFERENCE_MISMATCH");
        excluded(h, "NO_REFERENCE");
        long seat = counted(h, P.flat("100"));
        league(h, seat);
        league(h, pending);

        ManagerLeaderboardDto.Row r = row(board(LeaderboardKind.ALL), "lb-buckets");
        assertThat(r.counts()).isEqualTo(new ManagerLeaderboardDto.Counts(3, 1, 4, 2, 1, 1, 1, 1));
        ManagerLeaderboardDto.Counts c = r.counts();
        int sum = c.counted() + c.voided() + c.unfinished() + c.awaitingLeagueClose() + c.pending() + c.notStarted()
                + c.mismatch() + c.noReference();
        assertThat(sum).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM season WHERE member_id = ?", Integer.class, h));
        // only the three counted seasons enter L: 40, 50, 60 → mean 50, sd 10, L 45
        assertThat(r.l()).isEqualByComparingTo("45.00");
        assertThat(r.seasonsUsed()).isEqualTo(3);
    }

    @Test
    void 기다리는_시즌은_전체_pendingSeasons와_자기_칸에_센다() {
        int before = board(LeaderboardKind.ALL).pendingSeasons();
        long h = human("lb-pending");
        seasons(h, "50", "50", "50");
        season(h, "FINISHED");
        ManagerLeaderboardDto after = board(LeaderboardKind.ALL);
        assertThat(after.pendingSeasons()).isEqualTo(before + 1);
        assertThat(row(after, "lb-pending").counts().pending()).isEqualTo(1);
    }

    @Test
    void 리그가_닫히면_자리가_집계된다() {
        long h = human("lb-league");
        seasons(h, "50", "50");
        long seat = counted(h, P.flat("80"));
        long league = league(h, seat);
        assertThat(board(LeaderboardKind.ALL).rows()).isEmpty();

        closeLeague(league);
        ManagerLeaderboardDto.Row r = row(board(LeaderboardKind.ALL), "lb-league");
        assertThat(r.counts().counted()).isEqualTo(3);
        assertThat(r.counts().awaitingLeagueClose()).isZero();
        assertThat(r.trimmedMean()).isEqualByComparingTo("60");
    }

    @Test
    void 포기했지만_끝난_시즌은_집계된다() {
        long h = human("lb-abandoned");
        seasons(h, "50", "50");
        long abandoned = counted(h, P.flat("50"));
        jdbc.update("UPDATE season SET abandoned_turn_no = 5 WHERE id = ?", abandoned);
        assertThat(row(board(LeaderboardKind.ALL), "lb-abandoned").counts().counted()).isEqualTo(3);
    }

    @Test
    void 열두_시즌이면_최근_열_시즌만_쓴다() {
        long h = human("lb-twelve");
        seasons(h, "0", "0", "10", "20", "30", "40", "50", "60", "70", "80", "90", "100");
        ManagerLeaderboardDto.Row r = row(board(LeaderboardKind.ALL), "lb-twelve");
        assertThat(r.counts().counted()).isEqualTo(12);
        assertThat(r.seasonsUsed()).isEqualTo(10);
        // newest 10 = 10..100; trim one each side → mean of 20..90 = 55
        assertThat(r.trimmedMean()).isEqualByComparingTo("55.00");
        CareerScore.Result expected = CareerScore.of(List.of("10", "20", "30", "40", "50", "60", "70", "80", "90", "100")
                .stream().map(BigDecimal::new).toList());
        assertThat(r.l()).isEqualTo(expected.l());
        assertThat(r.sampleSd()).isEqualTo(MoneyMath.round2(expected.sampleSd()));
    }

    @Test
    void 반올림한_L이_같으면_좋은_순위를_함께_받는다() {
        // S = 0.75 x + 0.25 × 50 under s69-v1: 50.003, 50.000 and 49.9955 all round to 50.00; 49.994 rounds to 49.99
        String[][] cases = {{"lb-tie-a", "50.004"}, {"lb-tie-b", "50"}, {"lb-tie-c", "49.994"}, {"lb-tie-d", "49.992"}};
        for (String[] c : cases) {
            long member = human(c[0]);
            for (int i = 0; i < 3; i++) {
                counted(member, new P(c[1], c[1], c[1], "50"));
            }
        }
        ManagerLeaderboardDto all = board(LeaderboardKind.ALL);
        assertThat(all.rows()).extracting(ManagerLeaderboardDto.Row::displayName, ManagerLeaderboardDto.Row::rank,
                r -> r.l().toPlainString()).containsExactly(tuple("lb-tie-a", 1, "50.00"), tuple("lb-tie-b", 1, "50.00"),
                        tuple("lb-tie-c", 1, "50.00"), tuple("lb-tie-d", 4, "49.99"));
    }

    @Test
    void 동률이면_쓴_시즌이_많은_쪽이_먼저다() {
        long few = human("lb-a-few");
        seasons(few, "50", "50", "50");
        long many = human("lb-b-many");
        seasons(many, "50", "50", "50", "50");
        assertThat(names(board(LeaderboardKind.ALL))).containsExactly("lb-b-many", "lb-a-few");
        assertThat(board(LeaderboardKind.ALL).rows()).extracting(ManagerLeaderboardDto.Row::rank).containsExactly(1, 1);
    }

    @Test
    void 배수가_높아도_L이_낮으면_아래다() {
        long rich = human("lb-rich");
        for (int i = 0; i < 3; i++) {
            counted(rich, new P("40", "40", "40", "40", "9.00000000", PROCESS_VERSION));
        }
        long steady = human("lb-steady");
        for (int i = 0; i < 3; i++) {
            counted(steady, new P("60", "60", "60", "60", "0.50000000", PROCESS_VERSION));
        }
        ManagerLeaderboardDto all = board(LeaderboardKind.ALL);
        assertThat(names(all)).containsExactly("lb-steady", "lb-rich");
        assertThat(row(all, "lb-rich").meanMultiple()).isEqualTo(new BigDecimal("9.00000000"));
        assertThat(row(all, "lb-rich").rank()).isEqualTo(2);
    }

    @Test
    void L과_시즌_수가_같으면_배수가_아니라_이름순이고_순위는_같다() {
        long a = human("lb-m-a");
        long b = human("lb-m-b");
        for (int i = 0; i < 3; i++) {
            counted(a, new P("50", "50", "50", "50", "0.50000000", PROCESS_VERSION));
            counted(b, new P("50", "50", "50", "50", "9.00000000", PROCESS_VERSION));
        }
        ManagerLeaderboardDto all = board(LeaderboardKind.ALL);
        assertThat(all.rows()).extracting(ManagerLeaderboardDto.Row::displayName, ManagerLeaderboardDto.Row::rank)
                .containsExactly(tuple("lb-m-a", 1), tuple("lb-m-b", 1));
    }

    @Test
    void 새_공식_행이_L을_바꾸고_부품은_그대로다() {
        long h = human("lb-formula");
        counted(h, new P("100", "0", "0", "0"));
        counted(h, new P("100", "0", "0", "0"));
        counted(h, new P("100", "0", "0", "0"));
        List<Map<String, Object>> partsBefore = jdbc.queryForList("SELECT * FROM season_manager_parts ORDER BY season_id");
        ManagerLeaderboardDto first = board(LeaderboardKind.ALL);
        assertThat(first.formula().version()).isEqualTo("s69-v1");
        assertThat(row(first, "lb-formula").l()).isEqualByComparingTo("30.00");

        newerFormula("s1-v1", "0.50", "0.20", "0.10", "0.20");
        ManagerLeaderboardDto second = board(LeaderboardKind.ALL);
        assertThat(second.formula().version()).isEqualTo("s1-v1");
        assertThat(second.formula().weights().ret()).isEqualTo(new BigDecimal("0.50"));
        assertThat(row(second, "lb-formula").l()).isEqualByComparingTo("50.00");
        assertThat(jdbc.queryForList("SELECT * FROM season_manager_parts ORDER BY season_id")).isEqualTo(partsBefore);
    }

    @Test
    void 과정_점수_판은_쓴_시즌의_판을_오름차순으로_나열한다() {
        long h = human("lb-versions");
        counted(h, new P("50", "50", "50", "50", "1.00000000", "s63-v3"));
        counted(h, new P("50", "50", "50", "50", "1.00000000", "s63-v1"));
        counted(h, new P("50", "50", "50", "50", "1.00000000", "s63-v3"));
        assertThat(board(LeaderboardKind.ALL).processScoreVersions()).containsExactly("s63-v1", "s63-v3");
    }

    @Test
    void 에이전트_행은_최근_시즌의_페르소나와_메모리를_보이고_소유자는_보이지_않는다() {
        long owner = human("lb-owner");
        long agent = agent(owner, "Patient", "fake/lb-p");
        countedAgent(agent, P.flat("50"), 1, null);
        countedAgent(agent, P.flat("50"), 1, null);
        countedAgent(agent, P.flat("50"), 2, 1);
        countedAgent(agent, P.flat("50"), 1, null);
        countedAgent(agent, P.flat("50"), 2, 3);

        ManagerLeaderboardDto.Row r = board(LeaderboardKind.AGENT).rows().get(0);
        assertThat(r.displayName()).isEqualTo("Patient v2 · fake/lb-p");
        assertThat(r.kind()).isEqualTo(MemberKind.AGENT);
        assertThat(r.agent().personaName()).isEqualTo("Patient v2");
        assertThat(r.agent().personaVersion()).isEqualTo(2);
        assertThat(r.agent().memoryVersion()).isEqualTo(3);
        assertThat(r.agent().personaVersionsUsed()).containsExactly(1, 2);
        assertThat(r.agent().modelId()).isEqualTo("fake/lb-p");
        assertThat(r.agent().failedTurns()).isZero();
        assertThat(r.agent().outlook()).isNull();
        assertThat(r.displayName()).doesNotContain("owner-typed-name");
        assertThat(r.ownedByMe()).isFalse();
        assertThat(r.me()).isFalse();
    }

    @Test
    void 최근_시즌에_에이전트_설정이_없으면_그_행만_빠지고_표는_답한다() {
        long owner = human("lb-cfg-owner");
        seasons(owner, "50", "50", "50");
        long broken = agent(owner, "Broken", "fake/lb-broken");
        agentSeasons(broken, 4, "70");
        counted(broken, P.flat("70"));
        long fine = agent(owner, "Fine", "fake/lb-fine");
        agentSeasons(fine, 5, "40");

        ManagerLeaderboardDto all = board(LeaderboardKind.ALL);
        assertThat(names(all)).containsExactly("lb-cfg-owner", "Fine · fake/lb-fine");
        assertThat(all.rows()).extracting(ManagerLeaderboardDto.Row::rank).containsExactly(1, 2);
        assertThat(names(board(LeaderboardKind.AGENT))).containsExactly("Fine · fake/lb-fine");
    }

    @Test
    void 탐지_결과는_보이기만_하고_순서와_구성을_바꾸지_않는다() {
        jdbc.update("DELETE FROM detection_metric");
        jdbc.update("DELETE FROM detection_test");
        long owner = human("lb-det-owner");
        long tested = agent(owner, "Tested", "fake/model-1");
        agentSeasons(tested, 5, "40");
        long other = agent(owner, "Other", "fake/lb-other");
        agentSeasons(other, 5, "60");
        long human = human("lb-det-human");
        seasons(human, "50", "50", "50");

        ManagerLeaderboardDto before = board(LeaderboardKind.ALL);
        assertThat(before.rows()).allSatisfy(r -> {
            if (r.agent() != null) {
                assertThat(r.agent().detection()).isEqualTo(
                        new ManagerLeaderboardDto.Detection(DetectionStatus.NOT_TESTED, null, null, null, null));
            }
        });

        detections.importSummary(JsonMapper.shared().readTree(DetectionFixtures.summary("b".repeat(64))));
        ManagerLeaderboardDto after = board(LeaderboardKind.ALL);
        assertThat(names(after)).isEqualTo(names(before));
        assertThat(after.rows()).extracting(ManagerLeaderboardDto.Row::rank)
                .isEqualTo(before.rows().stream().map(ManagerLeaderboardDto.Row::rank).toList());
        assertThat(after.unranked()).isEqualTo(before.unranked());
        ManagerLeaderboardDto.Detection d = row(after, "Tested · fake/model-1").agent().detection();
        assertThat(d.status()).isEqualTo(DetectionStatus.TESTED);
        assertThat(d.testVersion()).isEqualTo("detection-month/1");
        assertThat(d.sampleLayer()).isEqualTo("P0-08");
        assertThat(d.windows()).extracting(ManagerLeaderboardDto.Window::months, ManagerLeaderboardDto.Window::units)
                .containsExactly(tuple(1, 98), tuple(3, 94));
        ManagerLeaderboardDto.Metric first = d.windows().get(0).metrics().get(0);
        assertThat(first.accuracy().toPlainString()).isEqualTo("0.01020408");
        assertThat(first.baseRate().toPlainString()).isEqualTo("0.01000000");
        assertThat(row(after, "Other · fake/lb-other").agent().detection().status()).isEqualTo(DetectionStatus.NOT_TESTED);
    }
}