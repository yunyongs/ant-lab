package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.CashAccountRepository;
import antlab.member.MemberRepository;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.support.IntegrationTest;
import antlab.support.ReconDefinitions;
import antlab.support.SeasonAccountFixture;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 기록기: 시도 번호 배정(한 번호 공간), 코드 집합 검사(쓰기 전 거절), 일치 행도 기록, detail 이중 인코딩 없음. */
@IntegrationTest
@Transactional
class ReconciliationLogWriterTest {

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
    ReconciliationLogWriter writer;
    @Autowired
    ReconciliationLogRepository logs;
    @Autowired
    EntityManager em;

    SeasonAccountFixture fixture;
    Season season;

    @BeforeEach
    void setUp() {
        fixture = new SeasonAccountFixture(jdbc, members, seasons, accounts);
        season = fixture.newSeason(fixture.newMember());
        fixture.newAccount(season);
    }

    private long rows(int turn, int attempt) {
        em.flush();
        return jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = ? AND attempt_no = ?",
                Long.class, season.getId(), turn, attempt);
    }

    private long identityLast() {
        return jdbc.queryForObject("SELECT coalesce(pg_sequence_last_value(pg_get_serial_sequence('reconciliation_log', 'id')), 0)",
                Long.class);
    }

    private RuleResult synthetic(int ruleNo) {
        RuleCatalog.Rule rule = RuleCatalog.byRuleNo(ruleNo).orElseThrow();
        return new RuleResult(ruleNo, rule.checkCode(), ReconciliationStatus.MATCH, null, null, null, 0, null, null);
    }

    @Test
    void 열다섯_개를_쓰면_시도_1_다시_쓰면_2이고_턴이_다르면_다시_1이다() {
        List<RuleResult> results = service.evaluateManual(season.getId(), 0);
        assertThat(writer.record(season.getId(), 0, results)).isEqualTo(1);
        assertThat(writer.record(season.getId(), 0, results)).isEqualTo(2);
        assertThat(writer.record(season.getId(), 1, results)).isEqualTo(1);
        assertThat(rows(0, 1)).isEqualTo(15);
        assertThat(rows(0, 2)).isEqualTo(15);
        assertThat(rows(1, 1)).isEqualTo(15);
    }

    @Test
    void 일치한_행도_기록하고_불일치_행은_분류와_detail을_남긴다() {
        List<RuleResult> results = service.evaluateManual(season.getId(), 0);
        int attempt = writer.record(season.getId(), 0, results);
        em.flush();
        em.clear();
        // 새 시즌(원장 없음)이라 규칙 1·3 등 일부만 불일치 — 일치 행도 있어야 한다
        assertThat(results.stream().anyMatch(r -> r.status() == ReconciliationStatus.MATCH)).isTrue();
        List<String> stored = jdbc.queryForList(
                "SELECT check_code || ':' || status || ':' || coalesce(classification, '-') FROM reconciliation_log"
                        + " WHERE season_id = ? AND attempt_no = ? ORDER BY check_code", String.class, season.getId(), attempt);
        List<String> want = results.stream().map(r -> r.checkCode().name() + ":" + r.status().name() + ":"
                + (r.classification() == null ? "-" : r.classification().name())).sorted().toList();
        assertThat(stored).isEqualTo(want);
    }

    @Test
    void detail은_JSON_객체로_저장되고_이중_인코딩이_아니다() {
        List<RuleResult> results = service.evaluateManual(season.getId(), 0);
        writer.record(season.getId(), 0, results);
        em.flush();
        long mismatch = results.stream().filter(r -> r.detail() != null).count();
        assertThat(mismatch).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND detail IS NOT NULL"
                + " AND jsonb_typeof(detail) = 'object'", Long.class, season.getId())).isEqualTo(mismatch);
    }

    @Test
    void 열일곱_개_묶음도_받아들인다() {
        List<RuleResult> all = new ArrayList<>(service.evaluateManual(season.getId(), 0));
        all.add(synthetic(11));
        all.add(synthetic(19));
        assertThat(writer.record(season.getId(), 0, all)).isEqualTo(1);
        assertThat(rows(0, 1)).isEqualTo(17);
    }

    @Test
    void 열네_개_열여섯_개_중복_빈_묶음은_쓰기_전에_거절하고_번호표도_쓰지_않는다() {
        List<RuleResult> good = service.evaluateManual(season.getId(), 0);
        long before = identityLast();
        List<RuleResult> fourteen = good.subList(0, 14);
        List<RuleResult> sixteen = new ArrayList<>(good);
        sixteen.add(synthetic(11));
        List<RuleResult> duplicate = new ArrayList<>(good.subList(0, 14));
        duplicate.add(good.get(0));
        for (List<RuleResult> bad : List.of(fourteen, sixteen, duplicate, List.<RuleResult>of())) {
            assertThatThrownBy(() -> writer.record(season.getId(), 0, bad)).isInstanceOf(IllegalArgumentException.class);
        }
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season.getId()))
                .isZero();
        assertThat(identityLast()).isEqualTo(before);
    }

    @Test
    void 음수_턴과_없는_시즌은_거절한다() {
        List<RuleResult> good = service.evaluateManual(season.getId(), 0);
        assertThatThrownBy(() -> writer.record(season.getId(), -1, good)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.record(Long.MAX_VALUE, 0, good)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SQL_기록_뒤_Java_기록은_다음_번호를_받는다_한_번호_공간() {
        ReconDefinitions.load(jdbc);
        em.flush();
        // SQL 정의로 한 번 기록(시도 1) → Java 가 2, 다시 SQL 이 3
        jdbc.queryForObject("SELECT pg_temp.recon_log(?, 0::smallint, (SELECT jsonb_agg(to_jsonb(x)) FROM pg_temp.recon_rules(?, 0::smallint) x))",
                Short.class, season.getId(), season.getId());
        em.clear();
        List<RuleResult> results = service.evaluateManual(season.getId(), 0);
        assertThat(writer.record(season.getId(), 0, results)).isEqualTo(2);
        em.flush();
        Short third = jdbc.queryForObject("SELECT pg_temp.recon_log(?, 0::smallint, (SELECT jsonb_agg(to_jsonb(x)) FROM pg_temp.recon_rules(?, 0::smallint) x))",
                Short.class, season.getId(), season.getId());
        assertThat(third).isEqualTo((short) 3);
        // 두 시도(SQL 1, Java 2)의 행이 id·attempt_no·created_at 만 빼고 같다
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT check_code, status, expected_value, actual_value, diff_value, mismatch_count, classification, detail
                    FROM reconciliation_log WHERE season_id = ? AND attempt_no = 1
                    EXCEPT
                    SELECT check_code, status, expected_value, actual_value, diff_value, mismatch_count, classification, detail
                    FROM reconciliation_log WHERE season_id = ? AND attempt_no = 2) d""", Long.class,
                season.getId(), season.getId())).isZero();
    }
}
