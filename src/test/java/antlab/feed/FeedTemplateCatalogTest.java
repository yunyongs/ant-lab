package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.history.MoveDirection;
import antlab.season.FeedKind;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedTemplateCatalogTest {

    private static final List<String> FILES = List.of("kinds.csv", "variables.csv", "templates.csv", "denylist.csv", "notices.csv");
    private static final String INFLOW = "rise.inflow.v1,RISE,ACTIVE,ASSET,UP,,,,,false,{asset_name} 이달 {ret} — 자금 몰려";

    @TempDir
    Path tmp;

    private Path feedCopy(String name) throws IOException {
        Path dir = Files.createDirectory(tmp.resolve(name));
        for (String file : FILES) {
            Files.copy(Path.of("feed", file), dir.resolve(file));
        }
        return dir;
    }

    private static void append(Path dir, String file, String line) throws IOException {
        Files.writeString(dir.resolve(file), line + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    }

    private static void replace(Path dir, String file, String from, String to) throws IOException {
        String text = Files.readString(dir.resolve(file));
        assertThat(text).contains(from);
        Files.writeString(dir.resolve(file), text.replace(from, to));
    }

    private void assertRejects(Path dir, String expectedPart) {
        assertThatThrownBy(() -> FeedTemplateCatalog.fromDirectory(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedPart);
    }

    @Test
    void the_real_feed_files_load_from_the_classpath() {
        FeedTemplateCatalog catalog = new FeedTemplateCatalog();
        assertThat(catalog.kindTargets().keySet()).containsExactlyInAnyOrder(FeedKind.values());
        assertThat(catalog.variables()).hasSize(14);
        assertThat(catalog.templates()).hasSizeGreaterThanOrEqualTo(89);
        assertThat(catalog.notices()).extracting(FeedNotice::id).contains("season_start.disclosure.v1");
        assertThat(catalog.denyWords()).isNotEmpty();
    }

    @Test
    void the_classpath_copy_is_byte_for_byte_the_feed_directory() throws IOException {
        for (String file : FILES) {
            try (var in = FeedTemplateCatalogTest.class.getClassLoader().getResourceAsStream("feed/" + file)) {
                assertThat(in).as(file).isNotNull();
                assertThat(in.readAllBytes()).as(file).isEqualTo(Files.readAllBytes(Path.of("feed", file)));
            }
        }
    }

    @Test
    void the_classpath_reader_uses_the_class_loader_and_not_the_working_directory() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("cp/feed"));
        Path source = feedCopy("seed");
        for (String file : FILES) {
            Files.copy(source.resolve(file), root.resolve(file));
        }
        replace(tmp.resolve("cp"), "feed/notices.csv", "season_start.disclosure.v1", "season_start.marker.v1");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {tmp.resolve("cp").toUri().toURL()}, null)) {
            FeedTemplateCatalog catalog = new FeedTemplateCatalog(FeedFiles.classpath(loader));
            assertThat(catalog.notices()).extracting(FeedNotice::id).containsExactly("season_start.marker.v1");
        }
    }

    @Test
    void a_missing_file_stops_the_load() throws IOException {
        Path dir = feedCopy("missing");
        Files.delete(dir.resolve("notices.csv"));
        assertThatThrownBy(() -> FeedTemplateCatalog.fromDirectory(dir)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void only_active_templates_enter_a_pool() throws IOException {
        Path dir = feedCopy("retired");
        append(dir, "templates.csv", "rise.old.v1,RISE,RETIRED,ASSET,UP,,,,,false,{asset_name} {ret} 옛 문장");
        FeedTemplateCatalog catalog = FeedTemplateCatalog.fromDirectory(dir);
        List<FeedTemplate> pool = catalog.pool(FeedKind.RISE, "ASSET", MoveDirection.UP, 18, Set.of("asset_name", "ret"), false, null);
        assertThat(pool).isNotEmpty().extracting(FeedTemplate::id).doesNotContain("rise.old.v1");
        assertThat(catalog.templates()).extracting(FeedTemplate::id).contains("rise.old.v1");
        assertThat(catalog.active("rise.old.v1")).isEmpty();
    }

    @Test
    void a_pool_is_ordered_by_template_id_bytes_and_needs_every_slot_to_have_a_value() {
        FeedTemplateCatalog catalog = new FeedTemplateCatalog();
        List<FeedTemplate> pool = catalog.pool(FeedKind.RISE, "ASSET", MoveDirection.UP, 18,
                Set.of("asset_name", "group_name", "ret", "prev_ret", "streak", "attention_rank"), false, null);
        assertThat(pool).isNotEmpty();
        assertThat(pool.stream().map(FeedTemplate::id).toList()).isSortedAccordingTo(FeedHash::compareBytes);
        List<FeedTemplate> thin = catalog.pool(FeedKind.RISE, "ASSET", MoveDirection.UP, 18, Set.of("asset_name", "ret"), false, null);
        assertThat(thin).hasSizeLessThan(pool.size());
        assertThat(thin).allSatisfy(t -> assertThat(Set.of("asset_name", "ret")).containsAll(t.placeholders()));
    }

    @Test
    void an_event_pool_follows_the_hidden_flag_and_the_category() {
        FeedTemplateCatalog catalog = new FeedTemplateCatalog();
        assertThat(catalog.pool(FeedKind.EVENT, "NONE", null, null, Set.of("reaction"), true, null))
                .isNotEmpty().allMatch(FeedTemplate::categoryHidden);
        List<FeedTemplate> trade = catalog.pool(FeedKind.EVENT, "NONE", null, null, Set.of("reaction"), false, "TRADE");
        assertThat(trade).extracting(FeedTemplate::id).containsExactly("event.trade.v1");
        assertThat(catalog.pool(FeedKind.EVENT, "NONE", null, null, Set.of("reaction"), false, null)).isEmpty();
        assertThat(catalog.pool(FeedKind.EVENT, "NONE", null, null, Set.of("reaction"), false, "UNKNOWN")).isEmpty();
    }

    @Test
    void a_zero_percent_keeps_only_sentences_shaped_like_an_event_card() {
        FeedTemplateCatalog catalog = new FeedTemplateCatalog();
        List<FeedTemplate> zero = catalog.pool(FeedKind.RISE, "GROUP", MoveDirection.UP, 0, Set.of("group_name", "group_ret"), false,
                null);
        List<FeedTemplate> nonZero = catalog.pool(FeedKind.RISE, "GROUP", MoveDirection.UP, 3, Set.of("group_name", "group_ret"), false,
                null);
        assertThat(zero).isNotEmpty().hasSizeLessThan(nonZero.size());
        Set<String> eventShapes = Set.of("주요 지역 지정학 이슈 부각 — 이달 관련 시장 {pct_signed}");
        assertThat(zero.stream().map(t -> catalog.shape(t.text())).toList()).containsAnyElementsOf(eventShapes);
        assertThat(catalog.shape("{asset_name} {ret}% {nope}")).isEqualTo("{name} {pct_signed}% {?}");
    }

    @Test
    void a_duplicate_template_id_stops_the_load() throws IOException {
        Path dir = feedCopy("dup");
        append(dir, "templates.csv", INFLOW);
        assertRejects(dir, "ID_DUP rise.inflow.v1");
    }

    @Test
    void two_active_templates_of_one_kind_and_slug_stop_the_load() throws IOException {
        Path dir = feedCopy("twice");
        append(dir, "templates.csv", INFLOW.replace("inflow.v1", "inflow.v2"));
        assertRejects(dir, "ID_ACTIVE rise.inflow.v2");
    }

    @Test
    void a_placeholder_that_is_not_a_variable_stops_the_load() throws IOException {
        Path dir = feedCopy("slot");
        append(dir, "templates.csv", "rise.zzz.v1,RISE,ACTIVE,ASSET,UP,,,,,false,{asset_name} {zzz}");
        assertRejects(dir, "VAR_UNKNOWN zzz");
    }

    @Test
    void a_variable_used_by_a_kind_that_may_not_use_it_stops_the_load() throws IOException {
        Path dir = feedCopy("varkind");
        append(dir, "templates.csv", "event.zzz.v1,EVENT,ACTIVE,NONE,,,TRADE,,,false,{asset_name} 소식");
        assertRejects(dir, "VAR_KIND asset_name");
    }

    @Test
    void an_unknown_kind_and_a_target_the_kind_does_not_allow_stop_the_load() throws IOException {
        Path unknown = feedCopy("kind");
        append(unknown, "templates.csv", "rise.q.v1,SURGE,ACTIVE,ASSET,UP,,,,,false,{asset_name} {ret}");
        assertRejects(unknown, "unknown kind");
        Path target = feedCopy("target");
        append(target, "templates.csv", "spread.q.v1,SPREAD,ACTIVE,ASSET,UP,,,,,false,{asset_name} {ret}");
        assertRejects(target, "TARGET_KIND spread.q.v1");
        Path prefix = feedCopy("prefix");
        append(prefix, "templates.csv", "fall.q.v1,RISE,ACTIVE,ASSET,UP,,,,,false,{asset_name} {ret}");
        assertRejects(prefix, "ID_KIND fall.q.v1");
    }

    @Test
    void a_header_that_differs_stops_the_load() throws IOException {
        Path dir = feedCopy("header");
        replace(dir, "templates.csv", "template_id,kind", "kind,template_id");
        assertRejects(dir, "feed/templates.csv");
    }

    @Test
    void kinds_csv_must_equal_the_feed_kind_enum_and_the_check_constraint_literals() throws IOException {
        Path dir = feedCopy("kinds");
        replace(dir, "kinds.csv", "NUDGE,독려 알림,ALERT,ASSET;GROUP;NONE\n", "");
        assertRejects(dir, "KINDS_ERD");
        String ddl = Files.readString(Path.of("db/02_schema_season.sql"));
        Matcher check = Pattern.compile("ck_turn_feed_kind CHECK \\(kind IN \\(([^)]*)\\)\\)").matcher(ddl);
        assertThat(check.find()).isTrue();
        List<String> literals = Arrays.stream(check.group(1).split(",")).map(s -> s.strip().replace("'", "")).toList();
        assertThat(literals).containsExactlyInAnyOrderElementsOf(Arrays.stream(FeedKind.values()).map(Enum::name).toList());
        assertThat(new FeedTemplateCatalog().kindTargets().keySet().stream().map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(literals);
    }

    @Test
    void a_notice_with_a_digit_a_slot_a_latin_letter_a_percent_sign_or_a_denied_word_stops_the_load() throws IOException {
        for (String bad : List.of("3일 뒤 공개", "{asset_name} 공개", "ABC 공개", "100% 공개", "작년 결과 공개")) {
            Path dir = feedCopy("notice" + Math.abs(bad.hashCode()));
            append(dir, "notices.csv", "bad.notice.v1,ACTIVE,\"" + bad + "\"");
            assertRejects(dir, "notices.csv");
        }
        Path dup = feedCopy("noticedup");
        append(dup, "notices.csv", "season_start.disclosure.v1,RETIRED,공개");
        assertRejects(dup, "ID_DUP season_start.disclosure.v1");
        Path format = feedCopy("noticeid");
        append(format, "notices.csv", "Bad-Id,ACTIVE,공개");
        assertRejects(format, "ID_FORMAT");
    }
}
