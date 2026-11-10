package antlab.support;

import antlab.account.CashAccount;
import antlab.account.CashAccountRepository;
import antlab.member.Member;
import antlab.member.MemberRepository;
import antlab.season.Formation;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시험용 부모 행 만들기: member → season → cash_account 는 리포지토리로, 종목·월 블록은 가짜 값으로 JDBC 에 넣는다.
 * 번호는 가정하지 않고 만든 번호를 기억해 {@link #cleanUp()} 이 그것만 지운다(롤백하는 시험은 부를 필요 없다).
 */
public final class SeasonAccountFixture {

    private static final AtomicInteger MONTHS = new AtomicInteger();

    private final JdbcTemplate jdbc;
    private final MemberRepository members;
    private final SeasonRepository seasons;
    private final CashAccountRepository accounts;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> seasonIds = new ArrayList<>();
    private final List<Long> assetIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();
    private final List<Long> blockIds = new ArrayList<>();

    public SeasonAccountFixture(JdbcTemplate jdbc, MemberRepository members, SeasonRepository seasons,
            CashAccountRepository accounts) {
        this.jdbc = jdbc;
        this.members = members;
        this.seasons = seasons;
        this.accounts = accounts;
    }

    public Member newMember() {
        Member m = members.save(new Member("fixture-" + UUID.randomUUID() + "@example.test", "가짜 회원", "hash"));
        memberIds.add(m.getId());
        return m;
    }

    public Season newSeason(Member member) {
        Season s = seasons.save(new Season(member.getId(), 12, 7L, "fixture-1", LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 1, 2), new BigDecimal("10000000"), new BigDecimal("0.00015000"),
                new BigDecimal("0.00025000"), new BigDecimal("0.00400000"), new BigDecimal("-0.20000000"),
                new Formation(20, 30, 30, 20)));
        seasonIds.add(s.getId());
        return s;
    }

    public CashAccount newAccount(Season season) {
        return accounts.save(new CashAccount(season.getId()));
    }

    /** 가짜 종목 하나(그룹도 하나 만든다). */
    public long newAssetId() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        Long group = jdbc.queryForObject(
                "INSERT INTO asset_group (code, name, kind) VALUES (?, '가짜 그룹', 'SECTOR') RETURNING id",
                Long.class, "ZG" + suffix);
        groupIds.add(group);
        Long asset = jdbc.queryForObject("""
                INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
                VALUES ('KR', ?, '가짜 종목', 'STOCK', 'KRW', ?, DATE '2000-01-04', 'PRICE') RETURNING id
                """, Long.class, "Z" + suffix, group);
        assetIds.add(asset);
        return asset;
    }

    /** 가짜 월 블록 하나(달은 겹치지 않게 1000년부터 한 달씩). */
    public long newMonthBlockId() {
        LocalDate month = LocalDate.of(1000, 1, 1).plusMonths(MONTHS.getAndIncrement());
        Long block = jdbc.queryForObject(
                "INSERT INTO month_block (month_start) VALUES (?) RETURNING id", Long.class, month);
        blockIds.add(block);
        return block;
    }

    public void cleanUp() {
        SeasonAccountCleaner.delete(jdbc, seasonIds, memberIds, assetIds, groupIds, blockIds);
        seasonIds.clear();
        memberIds.clear();
        assetIds.clear();
        groupIds.clear();
        blockIds.clear();
    }
}
