package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * 복사 동일성: {@code ReconciliationLogRepository} 의 규칙 쿼리 15개는 {@code db/22_reconcile_season_def.sql} 의 규칙 함수 본문에
 * 아래 기계적 치환만 한 텍스트와 (공백 정규화 뒤) 글자 단위로 같다. 22 를 고치고 Java 를 안 고치면 이 시험이 깨진다.
 *
 * <ul>
 *   <li>S1 {@code p_season_id} → {@code CAST(:seasonId AS bigint)}, S2 {@code p_turn}·{@code p_upto} → {@code CAST(:turnNo AS smallint)}</li>
 *   <li>S3 {@code pg_temp.recon_detail(a.items)} → recon_detail 본문(CASE 식, {@code p_items} → {@code a.items})을 괄호로 감싼 것</li>
 *   <li>S4 규칙 12 의 {@code pg_temp.recon_expected_price(…) x} → 본문을 괄호로 감싸고 별칭 목록 {@code x(turn_no, asset_id, price)} 를 붙인 것.
 *       본문 안의 {@code pg_temp.recon_price(A, B)} 두 곳은 recon_price 본문(재귀 CTE)을 {@code p_start → (A)}, {@code p_factors → (B)} 로 바꾼 스칼라 부분 질의</li>
 *   <li>S5 바깥 껍데기: 열 별칭({@code "expectedValue"} 등)과 jsonb → text</li>
 * </ul>
 */
class ReconciliationSqlCopyTest {

    private static final Path DEFINITION = Path.of("db/22_reconcile_season_def.sql");

    /** 쿼리 메서드 → 22 의 규칙 함수. */
    static final Map<String, String> RULE_FUNCTIONS = new LinkedHashMap<>();

    static {
        RULE_FUNCTIONS.put("findRule01AccountOpening", "recon_r01_account_opening");
        RULE_FUNCTIONS.put("findRule02CashSum", "recon_r02_cash_sum");
        RULE_FUNCTIONS.put("findRule03LedgerChain", "recon_r03_ledger_chain");
        RULE_FUNCTIONS.put("findRule04OrderLedger", "recon_r04_order_ledger");
        RULE_FUNCTIONS.put("findRule06HoldingQty", "recon_r06_holding_qty");
        RULE_FUNCTIONS.put("findRule07OrderFill", "recon_r07_order_fill");
        RULE_FUNCTIONS.put("findRule08ExecValues", "recon_r08_exec_values");
        RULE_FUNCTIONS.put("findRule09ReservedZero", "recon_r09_reserved_zero");
        RULE_FUNCTIONS.put("findRule12TurnPrice", "recon_r12_turn_price");
        RULE_FUNCTIONS.put("findRule13FillPrice", "recon_r13_fill_price");
        RULE_FUNCTIONS.put("findRule14FeeRule", "recon_r14_fee_rule");
        RULE_FUNCTIONS.put("findRule15Formation", "recon_r15_formation");
        RULE_FUNCTIONS.put("findRule16FormationBind", "recon_r16_formation_bind");
        RULE_FUNCTIONS.put("findRule17LineupHolding", "recon_r17_lineup_holding");
        RULE_FUNCTIONS.put("findRule18LineupOverlap", "recon_r18_lineup_overlap");
    }

    private static String definition() throws IOException {
        return Files.readString(DEFINITION, StandardCharsets.UTF_8);
    }

    static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /** 함수 하나의 {@code $$ … $$} 사이 본문. 없으면 실패. */
    static String functionBody(String ddl, String name) {
        Matcher m = Pattern.compile("CREATE OR REPLACE FUNCTION pg_temp\\." + Pattern.quote(name)
                + "\\(.*?AS \\$\\$\\n(.*?)\\n\\$\\$;", Pattern.DOTALL).matcher(ddl);
        if (!m.find()) {
            throw new IllegalStateException("22 에 함수가 없다: " + name);
        }
        return m.group(1);
    }

    private static String bind(String text) {
        return text.replace("p_season_id", "CAST(:seasonId AS bigint)")
                .replaceAll("\\bp_turn\\b", "CAST(:turnNo AS smallint)")
                .replaceAll("\\bp_upto\\b", "CAST(:turnNo AS smallint)");
    }

    private static String detailInline(String ddl) {
        Matcher m = Pattern.compile("SELECT (CASE.*END)\\s*$", Pattern.DOTALL).matcher(functionBody(ddl, "recon_detail"));
        if (!m.find()) {
            throw new IllegalStateException("recon_detail 본문 모양이 다르다");
        }
        return "(" + normalize(m.group(1)).replace("p_items", "a.items") + ")";
    }

    private static String priceInline(String ddl, String start, String factors) {
        String body = normalize(functionBody(ddl, "recon_price"));
        return "(" + body.replace("p_start", "(" + start + ")").replace("p_factors", "(" + factors + ")") + ")";
    }

    private static String expectedPriceInline(String ddl) {
        String body = normalize(functionBody(ddl, "recon_expected_price"));
        body = body.replace("pg_temp.recon_price(pl.p0, ARRAY[]::numeric[])", priceInline(ddl, "pl.p0", "ARRAY[]::numeric[]"));
        body = body.replace("pg_temp.recon_price(f.p0, f.arr)", priceInline(ddl, "f.p0", "f.arr"));
        return "(" + body + ") x(turn_no, asset_id, price)";
    }

    /** 22 원문에 S1–S5 를 적용한 규칙 쿼리 텍스트(공백 정규화). */
    static String expectedQuery(String ddl, String function) {
        String body = functionBody(ddl, function);
        body = body.replace("pg_temp.recon_detail(a.items)", detailInline(ddl));
        body = body.replace("pg_temp.recon_expected_price(p_season_id, p_turn) x", expectedPriceInline(ddl));
        body = bind(body);
        if (body.contains("pg_temp") || body.matches("(?s).*\\bp_(season_id|turn|upto)\\b.*")) {
            throw new IllegalStateException("치환하지 못한 이름이 남았다: " + function);
        }
        return normalize("SELECT r.expected_value AS \"expectedValue\", r.actual_value AS \"actualValue\", "
                + "r.mismatch_count AS \"mismatchCount\", CAST(r.detail AS text) AS \"detail\" FROM ( " + body
                + " ) AS r(expected_value, actual_value, mismatch_count, detail)");
    }

    private static Map<String, String> queries() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Method m : ReconciliationLogRepository.class.getDeclaredMethods()) {
            Query q = m.getAnnotation(Query.class);
            if (q != null && RULE_FUNCTIONS.containsKey(m.getName())) {
                out.put(m.getName(), q.value());
            }
        }
        return out;
    }

    /** 틀린 메서드 이름 목록(없으면 빈 목록). */
    static List<String> differences(String ddl) {
        Map<String, String> actual = queries();
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> e : RULE_FUNCTIONS.entrySet()) {
            String query = actual.get(e.getKey());
            if (query == null || !normalize(query).equals(expectedQuery(ddl, e.getValue()))) {
                bad.add(e.getKey());
            }
        }
        return bad;
    }

    @Test
    void 규칙_쿼리_15개가_22의_본문_치환_사본과_글자_단위로_같다() throws IOException {
        assertThat(queries()).hasSize(15);
        assertThat(Arrays.stream(ReconciliationLogRepository.class.getDeclaredMethods()).filter(m -> m.getReturnType() == RuleRow.class).map(Method::getName).collect(Collectors.toSet())).as("RuleRow 를 돌려주는 메서드 = 대조 표").isEqualTo(RULE_FUNCTIONS.keySet());
        assertThat(differences(definition())).isEmpty();
    }

    @Test
    void 쿼리는_매개변수_이름_둘만_쓰고_22의_함수_이름을_남기지_않는다() {
        for (Map.Entry<String, String> e : queries().entrySet()) {
            assertThat(e.getValue()).as(e.getKey()).doesNotContain("pg_temp").doesNotContain("p_season_id");
            Matcher params = Pattern.compile("(?<!:):([A-Za-z]\\w*)").matcher(e.getValue());
            List<String> names = new ArrayList<>();
            while (params.find()) {
                names.add(params.group(1));
            }
            assertThat(names).as(e.getKey()).isNotEmpty().allMatch(n -> n.equals("seasonId") || n.equals("turnNo"));
        }
    }

    @Test
    void 함수_하나를_지운_사본은_추출기가_실패한다() throws IOException {
        String ddl = definition();
        String removed = ddl.replace("CREATE OR REPLACE FUNCTION pg_temp.recon_r09_reserved_zero(",
                "CREATE OR REPLACE FUNCTION pg_temp.recon_r09_renamed(");
        assertThatThrownBy(() -> differences(removed)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recon_r09_reserved_zero");
    }

    @Test
    void 본문_한_글자를_바꾼_사본은_그_규칙만_다르다고_나온다() throws IOException {
        String ddl = definition();
        // 규칙 9 본문의 비교 연산자 하나만 바꾼다
        String changed = ddl.replace("WHERE NOT x.has_acct OR x.rsv <> 0", "WHERE NOT x.has_acct OR x.rsv <> 1");
        assertThat(changed).isNotEqualTo(ddl);
        assertThat(differences(changed)).containsExactly("findRule09ReservedZero");
    }

    @Test
    void 도우미_함수_본문을_바꾸면_그것을_쓰는_규칙이_다르다고_나온다() throws IOException {
        String ddl = definition();
        String changed = ddl.replace("jsonb_array_length(p_items) > 100", "jsonb_array_length(p_items) > 101");
        assertThat(changed).isNotEqualTo(ddl);
        assertThat(differences(changed)).hasSize(15);
        String price = ddl.replace("SELECT ROUND(r.acc, 0) FROM r ORDER BY r.i DESC LIMIT 1",
                "SELECT ROUND(r.acc, 1) FROM r ORDER BY r.i DESC LIMIT 1");
        assertThat(price).isNotEqualTo(ddl);
        assertThat(differences(price)).containsExactly("findRule12TurnPrice");
    }
}
