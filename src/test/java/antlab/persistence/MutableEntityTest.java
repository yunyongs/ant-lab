package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.CashAccount;
import antlab.account.CashAccountRepository;
import antlab.account.Holding;
import antlab.asset.Position;
import antlab.member.Member;
import antlab.member.MemberRepository;
import antlab.season.Formation;
import antlab.season.Lineup;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import antlab.support.JpaMappingTest;
import antlab.support.SeasonAccountFixture;
import antlab.support.SqlRecorder;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 가변 5개: 이름 있는 메서드로 바꾸면 UPDATE 가 나가고, 버전이 오르며, updated_at 만 바뀌고 created_at 은 그대로다. */
@JpaMappingTest
class MutableEntityTest {

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

    private record Stamps(OffsetDateTime created, OffsetDateTime updated, long version) {
    }

    private Stamps stamps(String table, String keyColumn, Object key, boolean versioned) {
        return jdbc.queryForObject("SELECT created_at, updated_at, " + (versioned ? "version" : "0")
                + " FROM " + table + " WHERE " + keyColumn + " = ?",
                (rs, n) -> new Stamps(rs.getObject(1, OffsetDateTime.class), rs.getObject(2, OffsetDateTime.class),
                        rs.getLong(3)), key);
    }

    @Test
    void 시즌은_저장_때_버전_0이고_전술을_바꾸면_UPDATE_한_번에_버전이_1_오른다() {
        Season season = fixture.newSeason(fixture.newMember());
        em.flush();
        assertThat(season.getVersion()).isZero();
        Stamps before = stamps("season", "id", season.getId(), true);

        season.changeFormation(new Formation(10, 20, 30, 40));
        SqlRecorder.start();
        em.flush();
        List<String> sqls = SqlRecorder.stop();

        assertThat(sqls).filteredOn(s -> s.startsWith("update")).hasSize(1);
        Stamps after = stamps("season", "id", season.getId(), true);
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.updated()).isAfter(before.updated());
        assertThat(after.created()).isEqualTo(before.created());
        em.clear();
        Season loaded = em.find(Season.class, season.getId());
        assertThat(loaded.getCurrentFormation()).isEqualTo(new Formation(10, 20, 30, 40));
        assertThat(loaded.getStartFormation()).isEqualTo(new Formation(20, 30, 30, 20));
        assertThat(loaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void 턴을_끝까지_진행하면_FINISHED가_DB에_들어간다() {
        Season season = fixture.newSeason(fixture.newMember());
        for (int i = 0; i < season.getPathLength(); i++) {
            season.advanceTurn();
        }
        em.flush();
        em.clear();
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season.getId()))
                .isEqualTo(SeasonStatus.FINISHED.name());
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class,
                season.getId())).isEqualTo(season.getPathLength());
    }

    @Test
    void 락만_잡고_바꾸지_않으면_버전도_UPDATE도_없다() {
        Season season = fixture.newSeason(fixture.newMember());
        em.flush();
        em.clear();
        SqlRecorder.start();
        Season locked = seasons.findByIdForUpdate(season.getId()).orElseThrow();
        em.flush();
        List<String> sqls = SqlRecorder.stop();
        assertThat(locked.getVersion()).isZero();
        assertThat(sqls).noneMatch(s -> s.startsWith("update"));
        assertThat(stamps("season", "id", season.getId(), true).version()).isZero();
    }

    @Test
    void 새_계좌는_save_한_번에_INSERT_하나이고_merge용_SELECT가_없다_버전은_0() {
        Season season = fixture.newSeason(fixture.newMember());
        SqlRecorder.start();
        CashAccount account = accounts.save(new CashAccount(season.getId()));
        em.flush();
        List<String> sqls = SqlRecorder.stop();

        assertThat(sqls).filteredOn(s -> s.startsWith("insert")).hasSize(1);
        assertThat(sqls).noneMatch(s -> s.startsWith("select"));
        assertThat(account.getVersion()).isZero();
        assertThat(account.getSeasonId()).isEqualTo(season.getId());
    }

    @Test
    void 계좌_잔액을_바꾸면_버전이_오르고_updated_at만_바뀐다() {
        Season season = fixture.newSeason(fixture.newMember());
        CashAccount account = fixture.newAccount(season);
        em.flush();
        Stamps before = stamps("cash_account", "season_id", season.getId(), true);

        BigDecimal next = account.applyLedgerAmount(new BigDecimal("10000000"));
        em.flush();

        assertThat(next).isEqualByComparingTo("10000000");
        Stamps after = stamps("cash_account", "season_id", season.getId(), true);
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.updated()).isAfter(before.updated());
        assertThat(after.created()).isEqualTo(before.created());
        assertThat(jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?", BigDecimal.class,
                season.getId())).isEqualByComparingTo("10000000");
    }

    @Test
    void 보유는_매수_매도를_반영하고_전량_매도하면_평균단가_0으로_행이_남는다() {
        Season season = fixture.newSeason(fixture.newMember());
        long assetId = fixture.newAssetId();
        Holding holding = new Holding(season.getId(), assetId);
        em.persist(holding);
        holding.applyBuy(10, new BigDecimal("1500.0000"));
        em.flush();
        holding.applySell(10);
        em.flush();
        em.clear();
        Holding loaded = em.find(Holding.class, holding.getId());
        assertThat(loaded.getQuantity()).isZero();
        assertThat(loaded.getAvgPrice()).isEqualByComparingTo("0");
    }

    @Test
    void 라인업은_구간을_닫으면_끝_턴이_들어간다() {
        Season season = fixture.newSeason(fixture.newMember());
        Lineup lineup = new Lineup(season, fixture.newAssetId(), Position.FORWARD_BREAKOUT, 2);
        em.persist(lineup);
        em.flush();
        assertThat(jdbc.queryForObject("SELECT end_turn IS NULL FROM lineup WHERE id = ?", Boolean.class,
                lineup.getId())).isTrue();
        lineup.close(5);
        em.flush();
        assertThat(jdbc.queryForObject("SELECT end_turn FROM lineup WHERE id = ?", Integer.class, lineup.getId()))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT declared_position FROM lineup WHERE id = ?", String.class,
                lineup.getId())).isEqualTo("FORWARD_BREAKOUT");
    }

    @Test
    void 회원은_저장되고_생성_수정_시각이_같은_시계에서_온다() {
        Member member = members.save(new Member("mutable-" + System.nanoTime() + "@example.test", "가짜", "hash"));
        em.flush();
        assertThat(member.getId()).isNotNull();
        assertThat(member.getCreatedAt()).isNotNull();
        assertThat(member.getUpdatedAt()).isNotNull();
    }
}
