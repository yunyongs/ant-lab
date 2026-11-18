package antlab.collect.series;

import static antlab.collect.series.MacroFixtures.RUN_1;
import static antlab.collect.series.MacroFixtures.RUN_2;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.JpaMappingTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rules C7-C13 of erd 10.5, which DDL cannot enforce. Each query returns no row on a correct fixture and the planted
 * violation is caught. These are the reference queries for the writer (M1-07) and the ECOS collector (M1-08).
 */
@JpaMappingTest
class MacroDataRulesTest {

    @Autowired
    JdbcTemplate jdbc;

    private MacroFixtures fx;
    private long cpi;
    private long dgs2;
    private long ecos;
    private long fredRun1;
    private long fredRun2;
    private long ecosRun;

    @BeforeEach
    void correctFixture() {
        fx = new MacroFixtures(jdbc);
        cpi = fx.series("TEST_CPI", "FRED", "TESTCPI", true);
        dgs2 = fx.series("TEST_DGS2", "FRED", "TESTDGS2", false);
        ecos = fx.series("TEST_KR_CPI", "ECOS", "901Y009/0", true);
        fredRun1 = fx.run("FRED_SERIES", RUN_1);
        fredRun2 = fx.run("FRED_SERIES", RUN_2);
        ecosRun = fx.run("ECOS_SERIES", RUN_1);
        fx.observation(cpi, "1990-01-01", "132.3", null, null, 1, RUN_1, fredRun1);
        fx.observation(cpi, "1990-01-01", "132.1", "2002-02-20", null, 2, RUN_1, fredRun1);
        fx.observation(cpi, "2022-12-01", "300.974", "2023-01-12", null, 1, RUN_1, fredRun1);
        fx.observation(dgs2, "2025-11-12", "3.56", null, null, 1, RUN_1, fredRun1);
        fx.observation(dgs2, "2025-11-12", "3.57", null, null, 2, RUN_2, fredRun2);
        fx.observation(ecos, "2025-10-01", "100.5", null, "2025-11-07", 1, RUN_1, ecosRun);
    }

    private int violations(String rule) {
        return jdbc.queryForList(rule).size();
    }

    @Test
    void 올바른_고정본에서는_일곱_규칙이_모두_0행이다() {
        for (String rule : MacroRules.ALL) {
            assertThat(violations(rule)).as(rule).isZero();
        }
    }

    @Test
    void C7_빈티지_번호에_빈틈이_있으면_잡는다() {
        fx.observation(dgs2, "2025-11-12", "3.58", null, null, 4, "2030-01-06 09:00:00+09", fx.run("FRED_SERIES", "2030-01-06 09:00:00+09"));

        assertThat(violations(MacroRules.C7)).isEqualTo(1);
    }

    @Test
    void C7_첫_빈티지가_1이_아니면_잡는다() {
        fx.observation(dgs2, "2025-11-13", "3.58", null, null, 2, RUN_1, fredRun1);

        assertThat(violations(MacroRules.C7)).isEqualTo(1);
    }

    @Test
    void C8_관측일시가_줄면_잡고_빈티지_이력을_읽지_않는_계열은_같아도_잡는다() {
        fx.observation(dgs2, "2025-11-12", "3.59", null, null, 3, RUN_1, fredRun1);
        assertThat(violations(MacroRules.C8)).as("decreasing, and equal for a latest-value series").isEqualTo(1);
        fx.observation(cpi, "2022-12-01", "301.460", "2023-02-10", null, 2, RUN_1, fredRun1);
        assertThat(violations(MacroRules.C8)).as("equal observed_at is fine for a vintage-history series").isEqualTo(1);
        fx.observation(cpi, "2022-12-01", "301.423", "2024-02-09", null, 3, "2030-01-02 09:00:00+09", fredRun1);
        assertThat(violations(MacroRules.C8)).as("a decrease in a vintage-history series").isEqualTo(2);
    }

    @Test
    void C9_이어지는_빈티지의_값이_같으면_잡는다() {
        fx.observation(dgs2, "2025-11-12", "3.57", null, null, 3, "2030-01-06 09:00:00+09", fx.run("FRED_SERIES", "2030-01-06 09:00:00+09"));

        assertThat(violations(MacroRules.C9)).isEqualTo(1);
    }

    @Test
    void C10_FRED_행에_사용_가능일이_있으면_잡는다() {
        fx.observation(dgs2, "2025-11-13", "3.58", null, "2025-11-14", 1, RUN_1, fredRun1);

        assertThat(violations(MacroRules.C10)).isEqualTo(1);
    }

    @Test
    void C10_수정되지_않는_FRED_계열에_빈티지_날짜가_있으면_잡는다() {
        fx.observation(dgs2, "2025-11-13", "3.58", "2025-11-14", null, 1, RUN_1, fredRun1);

        assertThat(violations(MacroRules.C10)).isEqualTo(1);
    }

    @Test
    void C10_수정되는_FRED_계열의_둘째_빈티지에_날짜가_없으면_잡는다() {
        fx.observation(cpi, "2022-12-01", "301.460", null, null, 2, RUN_1, fredRun1);

        assertThat(violations(MacroRules.C10)).isEqualTo(1);
    }

    @Test
    void C10_빈티지_날짜가_줄면_잡는다() {
        fx.observation(cpi, "2022-12-01", "301.460", "2023-01-02", null, 2, RUN_1, fredRun1);

        assertThat(violations(MacroRules.C10)).isEqualTo(1);
    }

    @Test
    void C10_ECOS_행에_빈티지_날짜가_있으면_잡는다() {
        fx.observation(ecos, "2025-09-01", "99.5", "2025-10-07", null, 1, RUN_1, ecosRun);

        assertThat(violations(MacroRules.C10)).isEqualTo(1);
    }

    @Test
    void C11_관측일시가_실행_시작일시와_다르면_잡는다() {
        fx.observation(dgs2, "2025-11-13", "3.58", null, null, 1, RUN_2, fredRun1);

        assertThat(violations(MacroRules.C11)).isEqualTo(1);
    }

    @Test
    void C12_출처와_다른_잡의_실행이_가져온_행은_잡는다() {
        fx.observation(dgs2, "2025-11-13", "3.58", null, null, 1, RUN_1, ecosRun);
        fx.observation(ecos, "2025-09-01", "99.5", null, "2025-10-07", 1, RUN_1, fredRun1);

        assertThat(violations(MacroRules.C12)).isEqualTo(2);
    }

    @Test
    void C13_ECOS_행에_사용_가능일이_없으면_잡는다() {
        fx.observation(ecos, "2025-09-01", "99.5", null, null, 1, RUN_1, ecosRun);

        assertThat(violations(MacroRules.C13)).isEqualTo(1);
    }

    @Test
    void C13_ECOS_개정이_저장된_날보다_앞선_사용_가능일을_가지면_잡고_같거나_뒤면_통과한다() {
        long run2 = fx.run("ECOS_SERIES", RUN_2);
        fx.observation(ecos, "2025-10-01", "100.9", null, "2025-11-07", 2, RUN_2, run2);
        assertThat(violations(MacroRules.C13)).as("lag date 2025-11-07 is before the 2030 storing day").isEqualTo(1);

        long run3 = fx.run("ECOS_SERIES", "2030-01-05 09:00:00+09");
        fx.observation(ecos, "2025-09-01", "99.5", null, "2030-01-05", 1, "2030-01-05 09:00:00+09", run3);
        fx.observation(ecos, "2025-09-01", "99.6", null, "2030-01-06", 2, "2030-01-06 09:00:00+09", fx.run("ECOS_SERIES", "2030-01-06 09:00:00+09"));
        assertThat(violations(MacroRules.C13)).as("available on the day it was stored is allowed").isEqualTo(1);
    }

    @Test
    void C13_첫_빈티지는_사용_가능일이_저장일보다_앞서도_된다() {
        assertThat(violations(MacroRules.C13)).isZero();
    }
}
