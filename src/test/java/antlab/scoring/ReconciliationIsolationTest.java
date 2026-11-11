package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.CashAccountRepository;
import antlab.member.MemberRepository;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 평가는 시즌을 잠근 호출자 트랜잭션 안에서만(MANDATORY), READ COMMITTED 에서만 돈다. */
@IntegrationTest
class ReconciliationIsolationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRepository members;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    CashAccountRepository accounts;
    @Autowired
    ReconciliationService service;
    @Autowired
    ReconciliationLogRepository logs;
    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void 트랜잭션_없이_부르면_거절한다() {
        assertThatThrownBy(() -> service.evaluateManual(1L, 0)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void REPEATABLE_READ_트랜잭션에서는_쓰기_전에_거절하고_기록이_없다() {
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        TransactionTemplate setup = new TransactionTemplate(txManager);
        Season season = setup.execute(s -> {
            Season created = fixture.newSeason(fixture.newMember());
            fixture.newAccount(created);
            return created;
        });
        try {
            DefaultTransactionDefinition repeatable = new DefaultTransactionDefinition();
            repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            TransactionTemplate tx = new TransactionTemplate(txManager, repeatable);
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> service.evaluateManual(season.getId(), 0)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ COMMITTED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class,
                    season.getId())).isZero();
            // 같은 시즌, 기본 격리 수준이면 돈다
            java.util.List<RuleResult> rows = new TransactionTemplate(txManager).execute(s -> service.evaluateManual(season.getId(), 0));
            assertThat(rows).hasSize(15);
        } finally {
            fixture.cleanUp();
        }
    }
}
