package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.CheckConstraintCatalog;
import antlab.support.IntegrationTest;
import antlab.support.ReconDefinitions;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 규칙 목록 사본(17행) = 22 의 {@code recon_code} 뷰 = {@link CheckCode} 상수 = DB CHECK 목록. */
@IntegrationTest
@Transactional
class RuleCatalogTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 규칙_목록은_22의_recon_code_17행과_같다() {
        ReconDefinitions.load(jdbc);
        List<String> sql = jdbc.query(
                "SELECT rule_no || ':' || check_code || ':' || manual || ':' || scalar FROM pg_temp.recon_code ORDER BY rule_no",
                (rs, i) -> rs.getString(1));
        List<String> java = RuleCatalog.allRules().stream()
                .map(r -> r.ruleNo() + ":" + r.checkCode().name() + ":" + r.manual() + ":" + r.scalar()).toList();
        assertThat(java).hasSize(17).isEqualTo(sql);
        assertThat(RuleCatalog.manualRules()).hasSize(15);
        assertThat(RuleCatalog.manualRules().stream().filter(RuleCatalog.Rule::scalar)).hasSize(5);
    }

    @Test
    void 검사_코드_상수와_DB_CHECK_목록과_규칙_목록이_같은_17개다() {
        Set<String> constants = Arrays.stream(CheckCode.values()).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> db = CheckConstraintCatalog.valueLists(jdbc).get("reconciliation_log.check_code");
        Set<String> catalog = RuleCatalog.allCodes().stream().map(Enum::name).collect(Collectors.toSet());
        assertThat(constants).hasSize(17).isEqualTo(db).isEqualTo(catalog);
        // 수동 15개 = 17개 − {VALUATION, POSITION_VALUE}
        assertThat(RuleCatalog.manualCodes()).doesNotContain(CheckCode.VALUATION, CheckCode.POSITION_VALUE).hasSize(15);
    }

    @Test
    void 규칙_번호로_찾고_없는_번호는_비어_있다() {
        assertThat(RuleCatalog.byRuleNo(12)).get().extracting(RuleCatalog.Rule::checkCode).isEqualTo(CheckCode.TURN_PRICE);
        assertThat(RuleCatalog.byRuleNo(5)).isEmpty();
        assertThat(RuleCatalog.byRuleNo(10)).isEmpty();
    }
}
