package antlab.leaderboard;

import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FakeEngineServer;
import antlab.support.TestTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticks of the parts job against the real service and a fake engine, with a clock the test moves: one season per tick,
 * a failed season waits and the next one goes first, it comes back after its wait, a later season of the same member
 * waits with it, an outage (also while scoring) stops the tick without adding a wait, and an administrator run during a
 * slow tick skips. The test profile has the flag off, so the context holds no job and nothing ticks on its own.
 */
class ManagerPartsJobTest extends ManagerPartsTestBase {

    private static final Duration DELAY = Duration.ofSeconds(60);

    @Autowired
    ApplicationContext context;
    @Autowired
    TestTokens tokens;

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-11-24T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovingClock clock = new MovingClock();

    private ManagerPartsJob job() {
        return new ManagerPartsJob(parts, clock, new ManagerPartsJobProperties(false, DELAY, Duration.ofHours(1)));
    }

    private long rowsFor(long season) {
        return jdbc.queryForObject("SELECT count(*) FROM season_manager_parts WHERE season_id = ?", Long.class, season);
    }

    @Test
    void theTestProfileHasNoJob() {
        assertThat(context.getBeanProvider(ManagerPartsJob.class).getIfAvailable()).isNull();
        assertThat(context.getBeanNamesForType(ManagerPartsJobConfig.class)).isEmpty();
    }

    @Test
    void oneSeasonPerTickEarliestFinishFirst() throws Exception {
        long first = finishedSeason();
        long second = finishedSeason();
        scoreAll();
        partsAnswerOnce(200, goodAnswer(first));
        partsAnswer(200, goodAnswer(second));
        ManagerPartsJob job = job();

        job.tick();
        assertThat(rowsFor(first)).isOne();
        assertThat(rowsFor(second)).isZero();

        job.tick();
        assertThat(rowsFor(second)).isOne();
        assertThat(partsCalls()).isEqualTo(2);

        job.tick();
        assertThat(partsCalls()).as("an empty queue calls nothing").isEqualTo(2);
    }

    @Test
    void aFailedSeasonWaitsWhileTheNextGoesAndComesBackAfterItsWait() throws Exception {
        long failing = finishedSeason();
        long next = finishedSeason();
        scoreAll();
        partsAnswerOnce(400, FakeEngineServer.fixture("problem-invalid-argument.json"));
        partsAnswer(200, goodAnswer(next));
        ManagerPartsJob job = job();

        job.tick();
        assertThat(rowsFor(failing)).isZero();
        assertThat(job.waitingSeasons()).isOne();

        clock.advance(DELAY);
        job.tick();
        assertThat(rowsFor(next)).as("the waiting season is skipped").isOne();
        assertThat(partsCalls()).isEqualTo(2);

        clock.advance(DELAY.minusSeconds(1));
        job.tick();
        assertThat(partsCalls()).as("still waiting: the wait is two delays after the failure").isEqualTo(2);

        clock.advance(Duration.ofSeconds(1));
        partsAnswer(200, goodAnswer(failing));
        job.tick();
        assertThat(rowsFor(failing)).as("tried again after its wait, and stored").isOne();
        assertThat(job.waitingSeasons()).isZero();
    }

    @Test
    void anOutageStopsTheTickWithoutAWait() throws Exception {
        long season = finishedSeason();
        scoreAll();
        partsAnswerOnce(503, FakeEngineServer.fixture("problem-engine-busy.json"));
        partsAnswer(200, goodAnswer(season));
        ManagerPartsJob job = job();

        job.tick();
        assertThat(rowsFor(season)).isZero();
        assertThat(job.waitingSeasons()).isZero();

        job.tick();
        assertThat(rowsFor(season)).as("the same season first on the next tick").isOne();
    }

    @Test
    void anOutageWhileScoringAddsNoWaitEither() throws Exception {
        long season = finishedSeason();
        scoreAnswerOnce(503, "problem-engine-busy.json");
        partsAnswer(200, FakeEngineServer.fixture("manager-parts-response.json"));
        ManagerPartsJob job = job();

        job.tick();
        assertThat(scoreRows(season)).isZero();
        assertThat(partsCalls()).isZero();
        assertThat(job.waitingSeasons()).as("an outage is not the season's failure").isZero();

        job.tick();
        assertThat(scoreRows(season)).as("the same season first on the next tick").isOne();
        assertThat(partsCalls()).isOne();
    }

    @Test
    void aLaterSeasonOfTheSameMemberWaitsWithAnEarlierOneThatFailedToScore() throws Exception {
        long first = finishedSeason();
        long second = finishedSeason();
        scoreAnswerOnce(400, "problem-invalid-argument.json");
        partsAnswer(200, FakeEngineServer.fixture("manager-parts-response.json"));
        ManagerPartsJob job = job();

        job.tick();
        assertThat(job.waitingSeasons()).isOne();
        assertThat(scoreCalls()).isOne();

        clock.advance(DELAY);
        job.tick();
        assertThat(scoreCalls()).as("the earlier season keeps its wait: no score call on the later one's behalf").isOne();
        assertThat(partsCalls()).isZero();
        assertThat(scoreRows(second)).isZero();
        assertThat(job.waitingSeasons()).as("the later season adds no wait of its own").isOne();

        clock.advance(DELAY);
        job.tick();
        assertThat(scoreRows(first)).isOne();
        assertThat(scoreRows(second)).isZero();
        assertThat(scoreCalls()).isEqualTo(2);
    }

    @Test
    void anAdministratorRunDuringASlowTickSkipsAndTheSeasonIsAskedOnce() throws Exception {
        long season = finishedSeason();
        scoreAll();
        partsAnswer(200, goodAnswer(season));
        ENGINE.delay(Duration.ofSeconds(2));
        ManagerPartsJob job = job();
        Thread tick = new Thread(job::tick, "parts-tick");
        tick.start();
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (partsCalls() == 0 && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertThat(partsCalls()).as("the tick is waiting for the engine").isOne();

        MockHttpServletResponse r = mvc.post().uri("/api/v1/admin/manager-parts/run?limit=1").requestAttr(ANONYMOUS, true)
                .header("Authorization", "Bearer " + tokens.admin(memberId())).exchange().getResponse();

        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode body = JsonMapper.shared().readTree(r.getContentAsString());
        assertThat(body.get("status").asString()).isEqualTo("ALREADY_RUNNING");
        assertThat(body.get("pending").asInt()).isOne();
        tick.join(Duration.ofSeconds(30));
        assertThat(tick.isAlive()).isFalse();
        assertThat(rowsFor(season)).isOne();
        assertThat(partsCalls()).as("the season was asked once").isOne();
    }

    @Test
    void aTickNeverThrows() {
        ManagerPartsJob broken = new ManagerPartsJob(new ManagerPartsService(null, null, null, null, null, null,
                new antlab.engine.client.EngineProperties("", "", Duration.ofSeconds(1), Duration.ofSeconds(3),
                        Duration.ofSeconds(15), Duration.ofSeconds(60), antlab.engine.client.EngineProperties.PathSourceMode.JAVA,
                        "m2-sb6-v1", 2000), null),
                clock, new ManagerPartsJobProperties(false, DELAY, Duration.ofHours(1)));
        broken.tick();
    }
}
