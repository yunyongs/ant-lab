package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import antlab.account.CashAccount;
import antlab.account.CashAccountRepository;
import antlab.member.MemberRepository;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 대사 설계가 기대는 트랜잭션 사실 두 가지를 실제로 확인한다(학습용 기록 겸 회귀). */
@IntegrationTest
class ReconciliationTransactionFactsTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRepository members;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    CashAccountRepository accounts;
    @Autowired
    EntityManager em;
    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void 읽기_전용_트랜잭션에서는_행_잠금_조회가_25006으로_거절된다() {
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        Season season = new TransactionTemplate(txManager).execute(s -> fixture.newSeason(fixture.newMember()));
        try {
            DefaultTransactionDefinition readOnly = new DefaultTransactionDefinition();
            readOnly.setReadOnly(true);
            TransactionTemplate tx = new TransactionTemplate(txManager, readOnly);
            Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(
                    s -> jdbc.queryForList("SELECT id FROM season WHERE id = ? FOR NO KEY UPDATE", season.getId())));
            assertThat(thrown).isNotNull();
            Throwable root = thrown;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(java.sql.SQLException.class);
            assertThat(((java.sql.SQLException) root).getSQLState()).isEqualTo("25006");
        } finally {
            fixture.cleanUp();
        }
    }

    @Test
    @Transactional
    void 대기_중인_엔티티_변경은_flush_뒤에야_네이티브_쿼리에_확실히_보인다() {
        SeasonAccountFixture fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        Season season = fixture.newSeason(fixture.newMember());
        em.flush();
        CashAccount account = accounts.save(new CashAccount(season.getId()));
        assertThat(account).isNotNull();
        // 같은 EntityManager 의 네이티브 쿼리는 영속성 컨텍스트가 건드린 표를 먼저 내보내는지 — 실측 X5 (결과를 단언으로 고정)
        Number viaEntityManager = (Number) em.createNativeQuery("SELECT count(*) FROM cash_account WHERE season_id = :s")
                .setParameter("s", season.getId()).getSingleResult();
        assertThat(viaEntityManager.longValue()).as("X5: 네이티브 쿼리 앞 자동 flush").isEqualTo(1L);
        // 어쨌든 evaluateManual 은 시작에 flush 를 명시한다
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_account WHERE season_id = ?", Long.class, season.getId()))
                .isEqualTo(1L);
    }
}
