package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.JpaMappingTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One bad row per V4 constraint, checked by SQLSTATE and constraint name. PostgreSQL reports only the first violated
 * CHECK by name (erd 2), so each case breaks exactly one rule. Each case runs in its own rolled-back transaction.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthConstraintTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String HASH = "a".repeat(64);

    @Autowired
    DataSource dataSource;

    /** One rolled-back transaction plus a row builder; only test constants and just-returned keys are concatenated into SQL. */
    final class Tx implements AutoCloseable {
        final Connection c;

        Tx() throws SQLException {
            c = dataSource.getConnection();
            c.setAutoCommit(false);
        }

        long member() throws SQLException {
            return queryLong("INSERT INTO member (email, name, password) VALUES ('auth-"
                    + SEQ.incrementAndGet() + "@example.invalid', '시험', 'x') RETURNING id");
        }

        /** Column name to SQL expression; unspecified columns default to a live root token. */
        long token(long memberId, UUID family, Map<String, String> over) throws SQLException {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("member_id", Long.toString(memberId));
            v.put("family_id", "'" + family + "'");
            v.put("token_hash", "'" + uniqueHash() + "'");
            v.put("created_at", "now()");
            v.put("expires_at", "now() + interval '1 day'");
            v.putAll(over);
            return queryLong("INSERT INTO refresh_token (" + String.join(", ", v.keySet()) + ") VALUES ("
                    + String.join(", ", v.values()) + ") RETURNING id");
        }

        long token(long memberId, UUID family) throws SQLException {
            return token(memberId, family, Map.of());
        }

        void revoke(long tokenId, String reason) throws SQLException {
            exec("UPDATE refresh_token SET revoked_at = now(), revoked_reason = '" + reason + "' WHERE id = " + tokenId);
        }

        void exec(String sql) throws SQLException {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }

        long queryLong(String sql) throws SQLException {
            try (PreparedStatement s = c.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }

        String queryString(String sql) throws SQLException {
            try (PreparedStatement s = c.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }

        @Override
        public void close() throws SQLException {
            c.rollback();
            c.close();
        }
    }

    interface Body {
        void run(Tx tx) throws Exception;
    }

    private static String uniqueHash() {
        return String.format("%064x", System.nanoTime() + SEQ.incrementAndGet());
    }

    /** The body must end in a SQL error with the expected SQLSTATE and constraint name (column name for NOT NULL). */
    private void expectViolation(String sqlState, String name, Body body) throws Exception {
        try (Tx tx = new Tx()) {
            body.run(tx);
            throw new AssertionError("제약 위반이 나야 한다: " + name);
        } catch (SQLException e) {
            String actual = nameIn(e.getMessage());
            assertThat(e.getSQLState()).as(name + " SQLSTATE").isEqualTo(sqlState);
            assertThat(actual).as(name + " 제약 이름").isEqualTo(name);
        }
    }

    /** Extracts the constraint name from the first line of the server message; a NOT NULL violation has none, so the column name. */
    private static String nameIn(String message) {
        String first = message.lines().findFirst().orElse(message);
        Matcher m = Pattern.compile("constraint \"([^\"]+)\"").matcher(first);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("column \"([^\"]+)\"").matcher(first);
        return m.find() ? m.group(1) : first;
    }

    // --- happy paths -----------------------------------------------------------------------------------------

    @Test
    void 역할을_안_주면_USER다() throws Exception {
        try (Tx tx = new Tx()) {
            long member = tx.member();

            assertThat(tx.queryString("SELECT role FROM member WHERE id = " + member)).isEqualTo("USER");
        }
    }

    @Test
    void 뿌리_자식_손자로_회전한다_폐기가_먼저이고_삽입이_나중이다() throws Exception {
        try (Tx tx = new Tx()) {
            long member = tx.member();
            UUID family = UUID.randomUUID();
            long root = tx.token(member, family);
            tx.revoke(root, "ROTATED");
            long child = tx.token(member, family, Map.of("parent_id", Long.toString(root)));
            tx.revoke(child, "ROTATED");
            long grandchild = tx.token(member, family, Map.of("parent_id", Long.toString(child)));

            assertThat(grandchild).isGreaterThan(child);
            assertThat(tx.queryLong("SELECT count(*) FROM refresh_token WHERE family_id = '" + family
                    + "' AND revoked_at IS NULL")).isEqualTo(1);
        }
    }

    @Test
    void 같은_회원의_다른_묶음은_동시에_살아_있을_수_있다() throws Exception {
        try (Tx tx = new Tx()) {
            long member = tx.member();
            tx.token(member, UUID.randomUUID());
            tx.token(member, UUID.randomUUID());

            assertThat(tx.queryLong("SELECT count(*) FROM refresh_token WHERE member_id = " + member
                    + " AND revoked_at IS NULL")).isEqualTo(2);
        }
    }

    @Test
    void 로그아웃_폐기_뒤에는_묶음에_살아_있는_토큰이_없다() throws Exception {
        try (Tx tx = new Tx()) {
            long member = tx.member();
            UUID family = UUID.randomUUID();
            tx.revoke(tx.token(member, family), "LOGOUT");

            assertThat(tx.queryLong("SELECT count(*) FROM refresh_token WHERE family_id = '" + family
                    + "' AND revoked_at IS NULL")).isZero();
        }
    }

    // --- cases that must fail --------------------------------------------------------------------------------

    record Case(String title, String sqlState, String name, Body body) {
        @Override
        public String toString() {
            return title;
        }
    }

    private static Body memberInsert(String roleSql) {
        return tx -> tx.exec("INSERT INTO member (email, name, password, role) VALUES ('role-"
                + SEQ.incrementAndGet() + "@example.invalid', '시험', 'x', " + roleSql + ")");
    }

    private static Body tokenWith(Map<String, String> over) {
        return tx -> tx.token(tx.member(), UUID.randomUUID(), over);
    }

    /** Creates and revokes a first token (so the family has no live token), then inserts a second row. */
    private static Body afterRevokedRoot(Map<String, String> over, boolean asChild) {
        return tx -> {
            long member = tx.member();
            UUID family = UUID.randomUUID();
            long root = tx.token(member, family);
            tx.revoke(root, "ROTATED");
            Map<String, String> second = new LinkedHashMap<>(over);
            if (asChild) {
                second.put("parent_id", Long.toString(root));
            }
            tx.token(member, family, second);
        };
    }

    static Stream<Arguments> negativeCases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("N1 role GUEST", "23514", "ck_member_role", memberInsert("'GUEST'")));
        cases.add(new Case("N2 role 소문자", "23514", "ck_member_role", memberInsert("'user'")));
        cases.add(new Case("N3 role NULL", "23502", "role", memberInsert("NULL")));
        cases.add(new Case("N4 해시에 대문자", "23514", "ck_refresh_token_hash_format",
                tokenWith(Map.of("token_hash", "'" + "A".repeat(64) + "'"))));
        cases.add(new Case("N5 해시 63자", "23514", "ck_refresh_token_hash_format",
                tokenWith(Map.of("token_hash", "'" + "a".repeat(63) + "'"))));
        cases.add(new Case("N6 해시 중복", "23505", "uq_refresh_token_token_hash", tx -> {
            long member = tx.member();
            tx.token(member, UUID.randomUUID(), Map.of("token_hash", "'" + HASH + "'"));
            tx.token(member, UUID.randomUUID(), Map.of("token_hash", "'" + HASH + "'"));
        }));
        cases.add(new Case("N7 만료 = 발급", "23514", "ck_refresh_token_expiry",
                tokenWith(Map.of("expires_at", "now()"))));
        cases.add(new Case("N8 폐기가 발급보다 이르다", "23514", "ck_refresh_token_revoked_at",
                tokenWith(Map.of("revoked_at", "now() - interval '1 hour'", "revoked_reason", "'LOGOUT'"))));
        cases.add(new Case("N9 폐기일시만", "23514", "ck_refresh_token_revoked_pair",
                tokenWith(Map.of("revoked_at", "now()"))));
        cases.add(new Case("N10 폐기 사유만", "23514", "ck_refresh_token_revoked_pair",
                tokenWith(Map.of("revoked_reason", "'LOGOUT'"))));
        cases.add(new Case("N11 사유 EXPIRED", "23514", "ck_refresh_token_revoked_reason",
                tokenWith(Map.of("revoked_at", "now()", "revoked_reason", "'EXPIRED'"))));
        cases.add(new Case("N12 없는 회원", "23503", "fk_refresh_token_member", tx -> tx.token(
                tx.queryLong("SELECT coalesce(max(id), 0) + 1000 FROM member"), UUID.randomUUID())));
        cases.add(new Case("N13 부모와 다른 묶음", "23503", "fk_refresh_token_parent", tx -> {
            long member = tx.member();
            long root = tx.token(member, UUID.randomUUID());
            tx.revoke(root, "ROTATED");
            tx.token(member, UUID.randomUUID(), Map.of("parent_id", Long.toString(root)));
        }));
        cases.add(new Case("N14 부모와 다른 회원", "23503", "fk_refresh_token_parent", tx -> {
            UUID family = UUID.randomUUID();
            long root = tx.token(tx.member(), family);
            tx.revoke(root, "ROTATED");
            tx.token(tx.member(), family, Map.of("parent_id", Long.toString(root)));
        }));
        cases.add(new Case("N15 부모 하나에 자식 둘", "23505", "uq_refresh_token_parent_id", tx -> {
            long member = tx.member();
            UUID family = UUID.randomUUID();
            long root = tx.token(member, family);
            tx.revoke(root, "ROTATED");
            long first = tx.token(member, family, Map.of("parent_id", Long.toString(root)));
            tx.revoke(first, "ROTATED");
            tx.token(member, family, Map.of("parent_id", Long.toString(root)));
        }));
        cases.add(new Case("N16 한 묶음에 뿌리 둘", "23505", "uq_refresh_token_family_root",
                afterRevokedRoot(Map.of(), false)));
        cases.add(new Case("N17 부모가 살아 있는데 자식 삽입", "23505", "uq_refresh_token_family_live", tx -> {
            long member = tx.member();
            UUID family = UUID.randomUUID();
            long root = tx.token(member, family);
            tx.token(member, family, Map.of("parent_id", Long.toString(root)));
        }));
        cases.add(new Case("N18 부모 번호가 자기보다 크다", "23514", "ck_refresh_token_parent_before", tx -> {
            long member = tx.member();
            UUID family = UUID.randomUUID();
            tx.exec("INSERT INTO refresh_token (id, member_id, family_id, token_hash, expires_at, revoked_at,"
                    + " revoked_reason) OVERRIDING SYSTEM VALUE VALUES (900000, " + member + ", '" + family + "', '"
                    + uniqueHash() + "', now() + interval '1 day', now(), 'ROTATED')");
            tx.exec("INSERT INTO refresh_token (id, member_id, family_id, parent_id, token_hash, expires_at)"
                    + " OVERRIDING SYSTEM VALUE VALUES (899999, " + member + ", '" + family + "', 900000, '"
                    + uniqueHash() + "', now() + interval '1 day')");
        }));
        cases.add(new Case("N19 토큰이 있는 회원 삭제", "23503", "fk_refresh_token_member", tx -> {
            long member = tx.member();
            tx.token(member, UUID.randomUUID());
            tx.exec("DELETE FROM member WHERE id = " + member);
        }));
        cases.add(new Case("N20 family_id NULL", "23502", "family_id", tx -> {
            long member = tx.member();
            tx.exec("INSERT INTO refresh_token (member_id, family_id, token_hash, expires_at) VALUES (" + member
                    + ", NULL, '" + uniqueHash() + "', now() + interval '1 day')");
        }));
        cases.add(new Case("N21 parent_id = id", "23514", "ck_refresh_token_parent_before", tx -> {
            long member = tx.member();
            tx.exec("INSERT INTO refresh_token (id, member_id, family_id, parent_id, token_hash, expires_at)"
                    + " OVERRIDING SYSTEM VALUE VALUES (900001, " + member + ", '" + UUID.randomUUID()
                    + "', 900001, '" + uniqueHash() + "', now() + interval '1 day')");
        }));
        return cases.stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("negativeCases")
    void 잘못된_행은_기대한_제약이_막는다(Case c) throws Exception {
        expectViolation(c.sqlState(), c.name(), c.body());
    }

    @Test
    void 사례는_스물한_개다() {
        assertThat(negativeCases().count()).isEqualTo(21);
    }

    /** Pins the premise the case design relies on: with several rules broken, only the first by name is reported. */
    @Test
    void 여러_CHECK를_어기면_이름_순_첫_하나만_보고된다() throws Exception {
        expectViolation("23514", "ck_refresh_token_expiry", tx -> tx.token(tx.member(), UUID.randomUUID(), Map.of(
                "token_hash", "'" + "A".repeat(64) + "'", "expires_at", "now()")));
    }
}
