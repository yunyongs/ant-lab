package antlab.api.season;

import static antlab.api.season.AbandonSupport.count;
import static antlab.api.season.AbandonSupport.status;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import antlab.common.error.DomainException;
import antlab.scoring.ReconciliationLogWriter;
import antlab.season.SeasonAbandonService;
import antlab.season.TurnFacade;
import antlab.support.IntegrationTest;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction boundaries of MVP-14: the outer abandon service has no transaction, each auto turn is its own transaction
 * of {@code TurnService.advanceAbandoned}, the service refuses to run inside a transaction, and the two turn entries are
 * not interchangeable (the member route never advances an abandoning season, the auto route never an in-progress one).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonAbandonFacadeTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    SeasonAbandonService abandons;
    @Autowired
    TurnFacade facade;
    @MockitoSpyBean
    ReconciliationLogWriter logWriter;

    @Test
    void theOuterServicesHaveNoTransactionAndEveryAutoTurnIsItsOwnAdvanceAbandonedTransaction() throws Exception {
        assertThat(SeasonAbandonService.class.isAnnotationPresent(Transactional.class)).isFalse();
        for (Method m : SeasonAbandonService.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(Transactional.class)).as(m.getName()).isFalse();
        }
        long season = startSeason(mvc);
        List<String> names = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            names.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return invocation.callRealMethod();
        }).when((ReconciliationLogWriter) AopTestUtils.getUltimateTargetObject(logWriter)).record(anyLong(), anyInt(), anyList());

        abandons.abandon(season, 0);

        assertThat(names).hasSize(12);
        assertThat(names).allSatisfy(name -> assertThat(name).endsWith("antlab.season.TurnService.advanceAbandoned"));
        assertThat(status(jdbc, season)).isEqualTo("FINISHED");
    }

    @Test
    void callingTheAbandonServiceInsideATransactionWritesNothing() throws Exception {
        long season = startSeason(mvc);
        new TransactionTemplate(txManager).executeWithoutResult(s -> assertThatThrownBy(() -> abandons.abandon(season, 0))
                .isInstanceOf(IllegalStateException.class));
        assertThat(status(jdbc, season)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT abandoned_turn_no FROM season WHERE id = ?", Integer.class, season)).isNull();
        assertThat(count(jdbc, "turn", season)).isZero();
    }

    @Test
    void theAutoRouteRefusesAnInProgressSeasonAndTheMemberRouteRefusesAnAbandoningOne() throws Exception {
        long season = startSeason(mvc);
        assertThatThrownBy(() -> facade.advanceAbandoned(season, 0)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getCode().name()).isEqualTo("SEASON_NOT_ACTIVE"));
        assertThat(count(jdbc, "turn", season)).isZero();

        jdbc.update("UPDATE season SET status = 'ABANDONING', abandoned_turn_no = 0 WHERE id = ?", season);
        assertThatThrownBy(() -> facade.advance(season, 0)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getCode().name()).isEqualTo("SEASON_NOT_ACTIVE"));
        assertThat(count(jdbc, "turn", season)).isZero();
        assertThat(facade.advanceAbandoned(season, 0).turnNo()).isEqualTo(1);
    }

    @Test
    void theAutoRouteChecksHaltedBeforeTheStatusAndTheStatusBeforeTheTurn() throws Exception {
        long season = startSeason(mvc);
        jdbc.update("UPDATE season SET status = 'ABANDONING', abandoned_turn_no = 0 WHERE id = ?", season);
        assertThatThrownBy(() -> facade.advanceAbandoned(season, 5)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getCode().name()).isEqualTo("TURN_MISMATCH"));
        jdbc.update("UPDATE season SET status = 'HALTED' WHERE id = ?", season);
        assertThatThrownBy(() -> facade.advanceAbandoned(season, 5)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getCode().name()).isEqualTo("SEASON_HALTED"));
    }
}
