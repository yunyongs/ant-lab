package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Second line of defence for the sentence rules; {@code feed/check_templates.sql} stays the primary check. Every template (ACTIVE and
 * RETIRED) and every notice is read through the Java catalog, so a file that passes SQL but breaks a rule here still fails the build.
 */
class FeedTextSafetyTest {

    private static final Pattern ALLOWED_LITERAL = Pattern.compile("[가-힣 ,.·—…!?()~'\"/\\-]*");
    private static final Pattern HANGUL_NUMERAL_DATE = Pattern.compile("[영일이삼사오육칠팔구십백천만억조]{2,}[년월]");

    private final FeedTemplateCatalog catalog = new FeedTemplateCatalog();

    private static String literal(String text) {
        return FeedTemplate.PLACEHOLDER.matcher(text).replaceAll("");
    }

    private void assertSafe(String id, String raw, String literal) {
        assertThat(raw).as(id + " has two spaces in a row").doesNotContain("  ");
        assertThat(literal).as(id + " literal uses only Hangul, spaces and the allowed punctuation").matches(ALLOWED_LITERAL);
        assertThat(literal).as(id).doesNotContain("%");
        assertThat(literal).as(id).doesNotContain("{").doesNotContain("}");
        assertThat(literal.codePoints().anyMatch(Character::isDigit)).as(id + " has a digit").isFalse();
        assertThat(literal.chars().anyMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= 0xFF21 && c <= 0xFF5A)))
                .as(id + " has a Latin letter").isFalse();
        assertThat(HANGUL_NUMERAL_DATE.matcher(literal).find()).as(id + " writes a date or year with Hangul numerals").isFalse();
        for (String word : catalog.denyWords()) {
            assertThat(literal).as(id + " contains the denied word " + word).doesNotContain(word);
        }
    }

    @Test
    void every_template_sentence_passes() {
        assertThat(catalog.templates()).isNotEmpty();
        for (FeedTemplate t : catalog.templates()) {
            assertSafe(t.id(), t.text(), literal(t.text()));
        }
    }

    @Test
    void every_notice_passes() {
        assertThat(catalog.notices()).isNotEmpty();
        for (FeedNotice n : catalog.notices()) {
            assertSafe(n.id(), n.text(), n.text());
        }
    }

    @Test
    void the_disclosure_notice_is_the_plan_sentence_word_for_word() throws IOException {
        FeedNotice disclosure = catalog.notices().stream().filter(n -> n.id().equals("season_start.disclosure.v1")).findFirst()
                .orElseThrow();
        assertThat(disclosure.active()).isTrue();
        assertThat(Files.readString(Path.of("docs/PLAN.md"))).contains("\"" + disclosure.text() + "\"");
    }

    @Test
    void the_checker_catches_the_violations_it_claims_to() {
        assertThat(Pattern.matches(ALLOWED_LITERAL.pattern(), "한글 문장 — 끝")).isTrue();
        assertThat(Pattern.matches(ALLOWED_LITERAL.pattern(), "abc")).isFalse();
        assertThat(Pattern.matches(ALLOWED_LITERAL.pattern(), "3일")).isFalse();
        assertThat(HANGUL_NUMERAL_DATE.matcher("이천이십육년").find()).isTrue();
        assertThat(HANGUL_NUMERAL_DATE.matcher("구원").find()).isFalse();
    }
}
