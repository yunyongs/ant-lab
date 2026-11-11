package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.CashAccountRepository;
import antlab.member.Member;
import antlab.member.MemberRepository;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.support.IntegrationTest;
import antlab.support.ReconDefinitions;
import antlab.support.RuleResultAssert;
import antlab.support.SeasonAccountFixture;
import antlab.support.SqlRecorder;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 같은 연결·같은 미커밋 상태에서 Java 평가({@code evaluateManual})와 SQL 정의({@code pg_temp.recon_rules})의 9컬럼이 같다.
 * 합성 상태는 시험 트랜잭션 안에서 만들고 롤백한다 — 표본이 없어도 늘 돈다. 기대값은 같은 상태에서 SQL 이 낸 값이고,
 * 이 파일에 숫자나 코드 집합을 손으로 적지 않는다(설계 의도 단언만 따로 둔다).
 */
@IntegrationTest
@Transactional
class ReconciliationServiceParityTest {

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
    EntityManager em;

    SeasonAccountFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        ReconDefinitions.load(jdbc);
    }

    private List<RuleResult> sql(long seasonId, int turn) {
        em.flush();
        return ReconDefinitions.sqlRules(jdbc, seasonId, turn);
    }

    private RuleResult rule(List<RuleResult> rows, CheckCode code) {
        return rows.stream().filter(r -> r.checkCode() == code).findFirst().orElseThrow();
    }

    @Test
    void 계좌와_원장이_없는_시즌에서_Java가_SQL과_같고_계좌_없음은_세_값이_없다() {
        Season season = fixture.newSeason(fixture.newMember());
        List<RuleResult> expected = sql(season.getId(), 0);
        List<RuleResult> actual = service.evaluateManual(season.getId(), 0);
        assertThat(actual).hasSize(15);
        RuleResultAssert.assertSame(expected, actual);
        // 설계 의도: 계좌가 없으면 규칙 2·9 는 비교 대상이 없어 세 값 NULL + 불일치 1
        for (CheckCode code : List.of(CheckCode.CASH_SUM, CheckCode.RESERVED_ZERO)) {
            RuleResult r = rule(actual, code);
            assertThat(r.mismatchCount()).as(code.name()).isEqualTo(1);
            assertThat(r.expectedValue()).isNull();
            assertThat(r.actualValue()).isNull();
            assertThat(r.diffValue()).isNull();
            assertThat(r.detail()).contains("NO_ACCOUNT");
        }
    }

    @Test
    void 계좌만_있고_원장이_빈_시즌에서_Java가_SQL과_같고_빈_원장_함의가_성립한다() {
        Season season = fixture.newSeason(fixture.newMember());
        fixture.newAccount(season);
        List<RuleResult> expected = sql(season.getId(), 0);
        List<RuleResult> actual = service.evaluateManual(season.getId(), 0);
        RuleResultAssert.assertSame(expected, actual);
        // 설계 의도(erd 7.5 규칙 2 비고): 빈 원장은 규칙 1(NO_LEDGER)이 잡고 규칙 2·3 은 일치한다
        assertThat(rule(actual, CheckCode.ACCOUNT_OPENING).detail()).contains("NO_LEDGER");
        assertThat(rule(actual, CheckCode.CASH_SUM).status()).isEqualTo(ReconciliationStatus.MATCH);
        assertThat(rule(actual, CheckCode.LEDGER_CHAIN).status()).isEqualTo(ReconciliationStatus.MATCH);
        assertThat(RuleResultAssert.mismatchCodes(actual)).isEqualTo(Set.of(CheckCode.ACCOUNT_OPENING));
    }

    @Test
    void 입금_한_행뿐인_시즌에서_Java가_SQL과_같고_전부_일치한다() {
        Season season = fixture.newSeason(fixture.newMember());
        fixture.newAccount(season);
        em.flush();
        jdbc.update("INSERT INTO cash_ledger (season_id, type, amount, balance_after) VALUES (?, 'DEPOSIT', 10000000, 10000000)",
                season.getId());
        jdbc.update("UPDATE cash_account SET balance = 10000000 WHERE season_id = ?", season.getId());
        em.clear();
        List<RuleResult> expected = sql(season.getId(), 0);
        List<RuleResult> actual = service.evaluateManual(season.getId(), 0);
        RuleResultAssert.assertSame(expected, actual);
        assertThat(RuleResultAssert.mismatchCodes(actual)).isEmpty();
        // 스칼라 규칙만 세 값이 있다
        assertThat(actual.stream().filter(r -> r.expectedValue() != null).map(RuleResult::checkCode))
                .containsExactlyInAnyOrder(CheckCode.CASH_SUM, CheckCode.ORDER_LEDGER, CheckCode.HOLDING_QTY,
                        CheckCode.RESERVED_ZERO, CheckCode.FEE_RULE);
    }

    @Test
    void 풀이_없는_시즌에서_규칙_12는_대상이_없어_Java가_SQL과_같다() {
        Season season = fixture.newSeason(fixture.newMember());
        List<RuleResult> expected = sql(season.getId(), 3);
        List<RuleResult> actual = service.evaluateManual(season.getId(), 3);
        RuleResultAssert.assertSame(expected, actual);
        assertThat(rule(actual, CheckCode.TURN_PRICE).status()).isEqualTo(ReconciliationStatus.MATCH);
    }

    @Test
    void 결과는_규칙_번호_순_열다섯_개다() {
        Season season = fixture.newSeason(fixture.newMember());
        List<RuleResult> actual = service.evaluateManual(season.getId(), 0);
        assertThat(actual.stream().map(RuleResult::ruleNo).toList())
                .isEqualTo(RuleCatalog.manualRules().stream().map(RuleCatalog.Rule::ruleNo).toList());
        assertThat(actual.stream().map(RuleResult::checkCode).toList()).isEqualTo(RuleCatalog.manualCodes());
    }

    @Test
    void 음수_검사_턴은_호출자_버그로_거절한다() {
        Season season = fixture.newSeason(fixture.newMember());
        assertThatThrownBy(() -> service.evaluateManual(season.getId(), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 없는_시즌이면_거절한다() {
        assertThatThrownBy(() -> service.evaluateManual(Long.MAX_VALUE, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시즌이 없다");
    }

    @Test
    void 호출자가_잠그지_않아도_첫_규칙_쿼리보다_먼저_시즌_잠금을_잡는다() {
        Season season = fixture.newSeason(fixture.newMember());
        em.flush();
        em.clear();
        SqlRecorder.start();
        service.evaluateManual(season.getId(), 0);
        List<String> sql = SqlRecorder.stop().stream().map(String::toLowerCase).toList();
        int lock = -1;
        for (int i = 0; i < sql.size(); i++) {
            if (sql.get(i).contains(" from season ") && sql.get(i).contains("for no key update")) {
                lock = i;
                break;
            }
        }
        assertThat(lock).as("시즌 잠금 쿼리가 나갔다").isGreaterThanOrEqualTo(0);
        // 잠금 앞에는 격리 수준 확인뿐이고, 규칙 쿼리(15개)는 모두 잠금 뒤에 나간다
        assertThat(sql.subList(0, lock)).allMatch(q -> q.contains("transaction_isolation"));
        assertThat(sql.subList(lock + 1, sql.size())).hasSizeGreaterThanOrEqualTo(15);
    }
}
