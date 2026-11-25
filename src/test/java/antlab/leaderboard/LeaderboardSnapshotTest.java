package antlab.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import antlab.support.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read runs on one snapshot: rows are committed (no test transaction), and between the board query and the viewer's
 * own query a second connection closes the league and stores the seat's parts. The answer must still be the state before
 * that write in every part.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LeaderboardSnapshotTest extends LeaderboardTestBase {

    @MockitoSpyBean
    LeaderboardQuery query;
    @Autowired
    MockMvcTester mvc;
    @Autowired
    TestTokens tokens;

    private final List<Long> members = new ArrayList<>();

    @Override
    @BeforeEach
    void isolateBoard() {
        // committed rows: other tests' parts rows are left alone
    }

    @AfterEach
    void cleanUp() {
        for (long m : members) {
            jdbc.update("DELETE FROM league_entry WHERE member_id = ?", m);
            jdbc.update("DELETE FROM season_manager_parts WHERE member_id = ?", m);
            jdbc.update("DELETE FROM season_score WHERE member_id = ?", m);
            jdbc.update("DELETE FROM turn WHERE season_id IN (SELECT id FROM season WHERE member_id = ?)", m);
            jdbc.update("DELETE FROM season WHERE member_id = ?", m);
            jdbc.update("DELETE FROM league WHERE owner_member_id = ?", m);
            jdbc.update("DELETE FROM member WHERE id = ?", m);
        }
    }

    private JsonNode read(long viewer) throws Exception {
        MockHttpServletResponse r = mvc.get().uri(LeaderboardApiTest.PATH)
                .header("Authorization", "Bearer " + tokens.user(viewer)).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    @Test
    void 조회_도중_리그가_닫히고_부품이_들어와도_답은_한_스냅샷이다() throws Exception {
        long a = human("lb-snap-" + System.nanoTime());
        members.add(a);
        counted(a, P.flat("50"));
        counted(a, P.flat("50"));
        counted(a, P.flat("50"));
        long seat = season(a, "FINISHED");
        long league = league(a, seat);

        AtomicBoolean fired = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        doAnswer(inv -> {
            Object board = inv.callRealMethod();
            if (fired.compareAndSet(false, true)) {
                Thread writer = new Thread(() -> {
                    try {
                        closeLeague(league);
                        parts(seat, a, P.flat("90"));
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                });
                writer.start();
                writer.join();
            }
            return board;
        }).when(query).board(anyString());

        JsonNode body = read(a);
        assertThat(fired).isTrue();
        assertThat(failure.get()).isNull();
        JsonNode me = body.get("me");
        assertThat(me.get("counted").asInt()).isEqualTo(3);
        assertThat(me.get("counts").get("awaitingLeagueClose").asInt()).isEqualTo(1);
        assertThat(me.get("seasons").get(0).get("excluded").asString()).isEqualTo("AWAITING_LEAGUE_CLOSE");
        assertThat(me.get("seasons").get(0).get("s").isNull()).isTrue();
        assertThat(me.get("seasons").get(0).get("parts").isNull()).isTrue();
        JsonNode row = null;
        for (JsonNode r : body.get("rows")) {
            if (r.get("me").asBoolean()) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("seasonsUsed").asInt()).isEqualTo(3);
        assertThat(row.get("counts")).isEqualTo(me.get("counts"));

        JsonNode after = read(a).get("me");
        assertThat(after.get("counted").asInt()).isEqualTo(4);
        assertThat(after.get("seasons").get(0).get("excluded").isNull()).isTrue();
        assertThat(after.get("seasons").get(0).get("s").asString()).isEqualTo("90.00");
    }
}
