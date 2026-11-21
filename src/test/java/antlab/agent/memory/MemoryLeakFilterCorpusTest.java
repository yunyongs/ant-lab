package antlab.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.agent.memory.MemoryLeakFilter.Code;
import antlab.agent.memory.MemoryLeakFilter.Hit;
import antlab.agent.memory.MemoryLeakFilter.Terms;
import antlab.agent.memory.MemoryLeakFilter.Verdict;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared corpus ({@code src/test/resources/agent/memory-filter}) against the Java filter: every text case must give
 * exactly the manifest's set of codes and every document exactly its ordered hits and oversize flag. The Python pre-check
 * reads the same files, so the two sides cannot drift apart. No Spring context, no database.
 */
class MemoryLeakFilterCorpusTest {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final Path CORPUS = Path.of("src/test/resources/agent/memory-filter");
    private static final Path TERMS_FILE = Path.of("src/main/resources/agent/leak-terms.txt");
    private static final JsonNode MANIFEST = MAPPER.readTree(read(CORPUS.resolve("manifest.json")));

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> titles(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(t -> out.add(t.asString()));
        return out;
    }

    /** The shipped terms file plus the corpus titles only (no tags.csv, so the corpus alone decides). */
    static MemoryLeakFilter corpusFilter() {
        return new MemoryLeakFilter(MemoryLeakFilter.loadTerms(bytes(TERMS_FILE), titles(MANIFEST.get("titles"))));
    }

    record Case(String id, String text, Set<String> codes) {
        @Override
        public String toString() {
            return id;
        }
    }

    record Doc(String file, String kind, boolean oversize, List<Hit> hits) {
        @Override
        public String toString() {
            return file;
        }
    }

    static Stream<Case> cases() {
        List<Case> out = new ArrayList<>();
        MANIFEST.get("cases").forEach(c -> {
            Set<String> codes = new TreeSet<>();
            c.get("codes").forEach(x -> codes.add(x.asString()));
            out.add(new Case(c.get("id").asString(), c.get("text").asString(), codes));
        });
        return out.stream();
    }

    static Stream<Doc> documents() {
        List<Doc> out = new ArrayList<>();
        MANIFEST.get("documents").forEach(d -> {
            List<Hit> hits = new ArrayList<>();
            d.get("hits").forEach(h -> hits.add(new Hit(h.get("pointer").asString(), Code.valueOf(h.get("code").asString()))));
            out.add(new Doc(d.get("file").asString(), d.get("kind").asString(), d.get("oversize").asBoolean(), hits));
        });
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void a_text_case_gives_exactly_the_manifest_codes(Case c) {
        Set<String> found = corpusFilter().scanText(c.text(), "/c").stream().map(h -> h.code().name())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(found).isEqualTo(c.codes());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documents")
    void a_document_gives_exactly_the_manifest_hits_in_order(Doc d) {
        JsonNode doc = MAPPER.readTree(read(CORPUS.resolve(d.file())));
        MemoryLeakFilter filter = corpusFilter();
        Verdict verdict = d.kind().equals("season-reflection/1") ? filter.checkReflection(doc) : filter.checkNote(doc);

        assertThat(verdict.hits()).isEqualTo(d.hits());
        assertThat(verdict.oversize()).isEqualTo(d.oversize());
    }

    @Test
    void the_manifest_lists_every_document_and_case_ids_are_unique() throws IOException {
        List<String> ids = cases().map(Case::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> files = Files.list(CORPUS.resolve("documents"))) {
            files.forEach(f -> onDisk.add("documents/" + f.getFileName()));
        }
        assertThat(documents().map(Doc::file).toList()).containsExactlyInAnyOrderElementsOf(onDisk);
    }

    @Test
    void every_code_has_a_positive_case_and_there_are_negatives() {
        Set<String> seen = cases().flatMap(c -> c.codes().stream()).collect(Collectors.toSet());
        assertThat(seen).containsExactlyInAnyOrderElementsOf(
                EnumSet.allOf(Code.class).stream().map(Enum::name).toList());
        assertThat(cases().filter(c -> c.codes().isEmpty()).count()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void a_verdict_holds_pointers_and_codes_only_never_the_text() {
        Verdict verdict = corpusFilter().checkNote(MAPPER.readTree(
                "{\"schema\":\"operating-note/1\",\"rules\":[\"2008년 9월 리먼 사태 -20%, +8%, +6%에 54,000원\"]}"));

        assertThat(verdict.hits()).isNotEmpty();
        assertThat(verdict.toString()).doesNotContain("2008").doesNotContain("리먼").doesNotContain("54,000");
        assertThat(verdict.hits()).allSatisfy(h -> assertThat(h.pointer()).matches("/rules/0#s[0-9]+"));
    }

    @Test
    void the_filter_version_is_mf1_and_twelve_hex_of_the_terms_file_hash() {
        Terms terms = corpusFilter().terms();

        assertThat(terms.version()).matches("mf1-[0-9a-f]{12}");
        byte[] raw = bytes(TERMS_FILE);
        byte[] changed = (new String(raw, java.nio.charset.StandardCharsets.UTF_8) + "\n추가항목\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(MemoryLeakFilter.loadTerms(changed, List.of()).version()).isNotEqualTo(terms.version());
    }

    @Test
    void the_shipped_terms_resource_is_the_file_on_disk() throws IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream(MemoryLeakFilters.TERMS_RESOURCE)) {
            assertThat(in).isNotNull();
            assertThat(in.readAllBytes()).isEqualTo(bytes(TERMS_FILE));
        }
    }

    @Test
    void a_byte_order_mark_and_inline_text_after_a_hash_do_not_change_the_entries() {
        byte[] withBom = ("﻿# comment\n리먼\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(MemoryLeakFilter.loadTerms(withBom, List.of()).terms()).containsExactly("리먼");
        byte[] indented = ("\u00a0# 메모\n\u3000# 메모\n리먼\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(MemoryLeakFilter.loadTerms(indented, List.of()).terms()).containsExactly("리먼");
    }

    @Test
    void the_caps_count_code_points_after_nfc_not_utf16_units() {
        // four strings of 120 Hangul + one of 119 Hangul and an astral character = 600 code points (601 UTF-16 units)
        String full = "가".repeat(120);
        String atCap = "{\"schema\":\"season-reflection/1\",\"good\":[\"" + full + "\",\"" + full + "\",\"" + full
                + "\"],\"mistakes\":[],\"ruleCandidates\":[\"" + full + "\",\"" + "가".repeat(119) + "😀\"]}";
        MemoryLeakFilter filter = corpusFilter();

        assertThat(filter.checkReflection(MAPPER.readTree(atCap)).oversize()).isFalse();
        assertThat(filter.checkReflection(MAPPER.readTree(atCap.replace("가".repeat(119) + "😀", full + "😀"))).oversize())
                .isTrue();
    }

    @Test
    void a_decomposed_hangul_string_is_measured_composed() {
        String decomposed = java.text.Normalizer.normalize("가".repeat(120), java.text.Normalizer.Form.NFD);
        JsonNode doc = MAPPER.readTree("{\"schema\":\"operating-note/1\",\"rules\":[\"" + decomposed + "\"]}");

        assertThat(decomposed.codePointCount(0, decomposed.length())).isEqualTo(240);
        assertThat(corpusFilter().checkNote(doc).oversize()).isFalse();
    }

    /** The title column of events/tags.csv (RFC 4180 quoting, titles hold no line breaks). */
    private static List<String> tagTitles() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("events/tags.csv"));
        List<String> header = csvFields(lines.get(0));
        int column = header.indexOf("title");
        List<String> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (!line.isBlank()) {
                out.add(csvFields(line).get(column));
            }
        }
        return out;
    }

    private static List<String> csvFields(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        out.add(field.toString());
        return out;
    }

    @Test
    void every_long_enough_events_tag_title_is_caught_as_an_event_term() throws IOException {
        List<String> titles = tagTitles();
        MemoryLeakFilter filter = new MemoryLeakFilter(MemoryLeakFilter.loadTerms(bytes(TERMS_FILE), titles));
        List<String> long_enough = titles.stream()
                .filter(t -> MemoryLeakFilter.compact(t).codePointCount(0, MemoryLeakFilter.compact(t).length())
                        >= MemoryLeakFilter.TITLE_MIN)
                .toList();

        assertThat(long_enough).isNotEmpty();
        assertThat(filter.terms().titles()).hasSize((int) long_enough.stream().map(MemoryLeakFilter::compact).distinct().count());
        for (String title : long_enough) {
            assertThat(filter.scanText(title, "/t")).as(title).anyMatch(h -> h.code() == Code.EVENT_TERM);
        }
    }

    @Test
    void every_shipped_persona_body_passes_line_by_line() throws IOException {
        MemoryLeakFilter filter = new MemoryLeakFilter(MemoryLeakFilter.loadTerms(bytes(TERMS_FILE), tagTitles()));
        List<Path> personas;
        try (Stream<Path> files = Files.list(Path.of("personas"))) {
            personas = files.filter(f -> f.getFileName().toString().endsWith(".md")).sorted().toList();
        }

        assertThat(personas).hasSizeGreaterThanOrEqualTo(4);
        for (Path file : personas) {
            String body = read(file).split("\n---\n", 2)[1];
            int number = 0;
            for (String line : body.split("\n", -1)) {
                assertThat(filter.scanText(line, "/" + number)).as("%s line %d", file.getFileName(), number).isEmpty();
                number++;
            }
        }
    }
}
