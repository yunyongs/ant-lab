package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.CashAccount;
import antlab.account.CashAccountRepository;
import antlab.account.CashLedger;
import antlab.account.LedgerType;
import antlab.account.Order;
import antlab.account.OrderSide;
import antlab.account.OrderSource;
import antlab.account.OrderStatus;
import antlab.account.OrderType;
import antlab.asset.Position;
import antlab.member.MemberRepository;
import antlab.season.Formation;
import antlab.season.FormationChange;
import antlab.season.Lineup;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.support.JpaMappingTest;
import antlab.support.SeasonAccountFixture;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 엔티티가 막지 못하는 실수를 DB 제약이 잡는가(실패해야 정상, 대표 세 건). 매핑이 열을 빠뜨리지 않았는지 보는 용도이고
 * 규칙 전체의 검증은 스키마 시험이 이미 한다.
 */
@JpaMappingTest
class SeasonAccountConstraintTest {

    @Autowired
    EntityManager em;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRepository members;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    CashAccountRepository accounts;

    SeasonAccountFixture fixture;

    @BeforeEach
    void 준비() {
        fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
    }

    private static SQLException sqlCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException s) {
                return s;
            }
        }
        throw new AssertionError("SQLException 원인이 없다", t);
    }

    @Test
    void 다른_시즌의_전술_변경번호로_주문을_넣으면_복합_FK가_거부한다() {
        Season mine = fixture.newSeason(fixture.newMember());
        Season other = fixture.newSeason(fixture.newMember());
        long assetId = fixture.newAssetId();
        FormationChange foreign = new FormationChange(other, 1, new Formation(20, 30, 30, 20),
                new Formation(10, 20, 30, 40), null, java.time.Instant.now());
        em.persist(foreign);

        Order order = new Order(mine.getId(), assetId, 1, OrderSide.BUY, OrderType.MARKET, 1, null,
                OrderStatus.FILLED, OrderSource.DIRECT, foreign.getId(), null, java.time.Instant.now());
        assertThatThrownBy(() -> em.persist(order)).satisfies(e -> {
            SQLException sql = sqlCause(e);
            assertThat(sql.getSQLState()).isEqualTo("23503");
            assertThat(sql.getMessage()).contains("fk_orders_formation_change");
        });
    }

    @Test
    void 입금_원장에_음수_금액을_넣으면_CHECK가_거부한다() {
        Season season = fixture.newSeason(fixture.newMember());
        CashAccount account = fixture.newAccount(season);
        CashLedger bad = new CashLedger(account, LedgerType.DEPOSIT, new BigDecimal("-100"), new BigDecimal("100"),
                null);
        assertThatThrownBy(() -> em.persist(bad)).satisfies(e -> {
            SQLException sql = sqlCause(e);
            assertThat(sql.getSQLState()).isEqualTo("23514");
            assertThat(sql.getMessage()).contains("ck_cash_ledger_amount_sign");
        });
    }

    @Test
    void 같은_시즌_같은_종목의_열린_라인업_둘은_부분_유일_인덱스가_거부한다() {
        Season season = fixture.newSeason(fixture.newMember());
        long assetId = fixture.newAssetId();
        em.persist(new Lineup(season, assetId, Position.MIDFIELDER, 0));
        Lineup dup = new Lineup(season, assetId, Position.DEFENDER, 1);
        assertThatThrownBy(() -> em.persist(dup)).satisfies(e -> {
            SQLException sql = sqlCause(e);
            assertThat(sql.getSQLState()).isEqualTo("23505");
            assertThat(sql.getMessage()).contains("uq_lineup_open");
        });
    }

    @Test
    void 닫힌_구간_뒤에는_같은_종목의_새_열린_구간을_넣을_수_있다() {
        Season season = fixture.newSeason(fixture.newMember());
        long assetId = fixture.newAssetId();
        Lineup first = new Lineup(season, assetId, Position.MIDFIELDER, 0);
        em.persist(first);
        first.close(2);
        em.flush();
        em.persist(new Lineup(season, assetId, Position.DEFENDER, 3));
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lineup WHERE season_id = ? AND asset_id = ?",
                Integer.class, season.getId(), assetId)).isEqualTo(2);
    }
}
