package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CheckVerdictParserTest {

    private static final String COL = "p0_05_check";

    @Test
    void 마지막_판정이_등록한_열의_PASS면_통과다() {
        assertThat(CheckVerdictParser.parse(COL + "\nPASS\n", COL).passed()).isTrue();
        // 앞에 진행 출력이 있어도, 빈 줄과 개행 형식이 달라도 끝 두 줄만 본다
        assertThat(CheckVerdictParser.parse("section|total\r\n0|3\r\n" + COL + "\r\nPASS\r\n\r\n", COL).passed()).isTrue();
    }

    @Test
    void 앞쪽에_PASS가_있어도_마지막이_FAIL이면_실패다() {
        String out = COL + "\nPASS\n" + COL + "\nFAIL\n";
        var verdict = CheckVerdictParser.parse(out, COL);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.reason()).contains("FAIL");
    }

    @Test
    void 판정_행이_없으면_실패다() {
        assertThat(CheckVerdictParser.parse("", COL).passed()).isFalse();
        assertThat(CheckVerdictParser.parse("PASS\n", COL).passed()).isFalse();
        assertThat(CheckVerdictParser.parse("section|total\n0|3\n", COL).passed()).isFalse();
    }

    @Test
    void 다른_검사_이름의_PASS는_실패다() {
        assertThat(CheckVerdictParser.parse("p0_06_check\nPASS\n", COL).passed()).isFalse();
    }

    @Test
    void PASS_뒤에_추가_결과가_있으면_실패다() {
        assertThat(CheckVerdictParser.parse(COL + "\nPASS\nn\n3\n", COL).passed()).isFalse();
        assertThat(CheckVerdictParser.parse(COL + "\nPASS\nPASS\n", COL).passed()).isFalse();
    }

    @Test
    void 소문자_pass나_공백이_섞인_값은_PASS가_아니다() {
        assertThat(CheckVerdictParser.parse(COL + "\npass\n", COL).passed()).isFalse();
        assertThat(CheckVerdictParser.parse(COL + "\nPASSED\n", COL).passed()).isFalse();
    }

    @Test
    void 실패_사유에_원문_출력이_들어가지_않는다() {
        var verdict = CheckVerdictParser.parse("secret_column\n123456.7800\n", COL);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.reason()).doesNotContain("secret_column").doesNotContain("123456");
    }

    @Test
    void 건수_열이_붙은_드릴_판정은_첫_칸과_실패_건수까지_본다() {
        String head = "p0_17_drill|total|pass|fail\n";
        assertThat(CheckVerdictParser.parseWithCounts(head + "PASS|20|20|0\n", "p0_17_drill").passed()).isTrue();
        assertThat(CheckVerdictParser.parseWithCounts(head + "FAIL|20|19|1\n", "p0_17_drill").passed()).isFalse();
        assertThat(CheckVerdictParser.parseWithCounts(head + "PASS|20|19|1\n", "p0_17_drill").passed()).isFalse();
        assertThat(CheckVerdictParser.parseWithCounts(head + "PASS|20|20|0\n", "p0_17_check").passed()).isFalse();
        assertThat(CheckVerdictParser.parseWithCounts(head + "PASS|20|20|0\nextra\n", "p0_17_drill").passed()).isFalse();
        // 건수 칸: 검사 0건, pass != total, 숫자 아님, 다른 머리
        assertThat(CheckVerdictParser.parseWithCounts(head + "PASS|0|0|0\n", "p0_17_drill").passed()).isFalse();
        assertThat(CheckVerdictParser.parseWithCounts(head + "PASS|20|19|0\n", "p0_17_drill").passed()).isFalse();
        assertThat(CheckVerdictParser.parseWithCounts(head + "PASS|x|20|0\n", "p0_17_drill").passed()).isFalse();
        assertThat(CheckVerdictParser.parseWithCounts("p0_17_drill|n|pass|fail\nPASS|20|20|0\n", "p0_17_drill").passed()).isFalse();
        // 한 열짜리 판정에는 쓰지 않는다
        assertThat(CheckVerdictParser.parseWithCounts("p0_17_drill\nPASS\n", "p0_17_drill").passed()).isFalse();
    }
}
