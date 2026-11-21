package antlab.agent.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Leak filter {@code memory-filter/1}, the authoritative side (the Python runner runs the same rules as a pre-check).
 * Pure and deterministic. The shared corpus {@code src/test/resources/agent/memory-filter} is the contract: its manifest
 * states the normalisation, the ASCII-only pattern classes, the sentence rule, the hit order and the code point caps,
 * and {@code MemoryLeakFilterCorpusTest} holds this class to the exact codes and order. A verdict carries pointers and
 * codes only, never the offending text.
 */
public final class MemoryLeakFilter {

    public static final String FILTER_ID = "memory-filter/1";
    public static final int REFLECTION_TOTAL_MAX = 600;
    public static final int NOTE_TOTAL_MAX = 2000;
    static final int FINGERPRINT_MIN = 3;
    /** Shorter titles are generic phrases that ordinary sentences also contain. */
    static final int TITLE_MIN = 7;

    public enum Code { YEAR, YEAR_MONTH, EVENT_TERM, FINGERPRINT, TURN_RETURN, PRICE }

    /** A pointer (JSON pointer of the string plus {@code #s<n>}) and a code. */
    public record Hit(String pointer, Code code) {
    }

    public record Verdict(List<Hit> hits, boolean oversize) {
        public Verdict {
            hits = List.copyOf(hits);
        }

        public boolean ok() {
            return hits.isEmpty() && !oversize;
        }
    }

    /** Compact dictionary entries, compact titles and the version label derived from the terms file bytes. */
    public record Terms(List<String> terms, List<String> titles, String version) {
        public Terms {
            terms = List.copyOf(terms);
            titles = List.copyOf(titles);
        }
    }

    // Explicit ASCII classes only (no \d, \w, \b, \s): normalize() has already folded every Nd digit to 0-9.
    private static final String SP = "[ \\t\\r\\n]";
    private static final String MONTH = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?"
            + "|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";
    private static final String YEAR4 = "(?:19|20)[0-9]{2}";
    // Korean durations and bond tenors that are not years ("10년 이상", "10년물").
    private static final String DURATION = "(?!" + SP + "*(?:이상|이하|미만|초과|동안|간|째|물|만기|넘게|가까이|가량|정도|만에|마다"
            + "|주기|짜리|차))";

    private static final List<Pattern> YEAR_PATTERNS = compile(
            "(?<![0-9])" + YEAR4 + "(?![0-9])",
            "(?<![0-9])0[0-9]년(?!차)",
            "(?<![0-9])[1-9][0-9]년(?!차)" + DURATION,
            "(?<![a-z0-9])['’‘][0-9]{2}(?![0-9])",
            "(?<![a-z])fy" + SP + "?[0-9]{2}",
            "(?<![a-z0-9.])[0-9]0s(?![a-z])",
            "(?<![a-z0-9])(?:[1-4]q|q[1-4]|[12]h|h[12])" + SP + "?['’]?[0-9]{2}(?![0-9])");

    private static final List<Pattern> YEAR_MONTH_PATTERNS = compile(
            "(?<![0-9])" + YEAR4 + SP + "*[-/.]" + SP + "*(?:0?[1-9]|1[0-2])(?![0-9])",
            "(?<![0-9])" + YEAR4 + SP + "*년" + SP + "*[0-9]{1,2}" + SP + "*월",
            "(?<![0-9])[0-9]{2}년" + SP + "*[0-9]{1,2}" + SP + "*월",
            "(?<![a-z])" + MONTH + "(?![a-z])" + SP + "*,?" + SP + "*" + YEAR4 + "(?![0-9])",
            "(?<![0-9])" + YEAR4 + SP + "*,?" + SP + "*(?<![a-z])" + MONTH + "(?![a-z])",
            "(?<![a-z])" + MONTH + "(?![a-z])" + SP + "*[0-9]{1,2}" + SP + "*,?" + SP + "*" + YEAR4 + "(?![0-9])",
            "(?<![0-9/])(?:0?[1-9]|1[0-2])" + SP + "*/" + SP + "*" + YEAR4 + "(?![0-9])",
            "(?<![0-9])" + YEAR4 + SP + "*년?" + SP + "*[1-4]" + SP + "*분기",
            "(?<![a-z0-9])(?:[1-4]q|q[1-4])" + SP + "*,?" + SP + "*" + YEAR4 + "(?![0-9])",
            "(?<![0-9])" + YEAR4 + SP + "*,?" + SP + "*q[1-4](?![0-9])");

    private static final String NUM = "[0-9]+(?:\\.[0-9]+)?";
    // A return-like number: signed percent, a percentage-point figure, a signed decimal, or an unsigned percent with a
    // fraction. Only a digit or a dot before the number blocks a match.
    private static final String RETURN_LIKE = "(?<![0-9.])[+-]" + SP + "?" + NUM + SP + "?%p?"
            + "|(?<![0-9.])" + NUM + SP + "?%p"
            + "|(?<![0-9.])[0-9]+\\.[0-9]+" + SP + "?%"
            + "|(?<![0-9.])[+-][0-9]+\\.[0-9]+(?![0-9])";
    private static final Pattern RETURN_LIKE_P = Pattern.compile(RETURN_LIKE);
    // With a movement word in the sentence, an unsigned integer percent is a return too.
    private static final Pattern DIRECTION = Pattern.compile(
            "상승|하락|올랐|오른|오르|떨어|내렸|내린|반등|급락|급등|폭락|폭등|뛰었|빠졌|rose|fell|gained|dropped|rallied|plunged|jumped");
    private static final Pattern RETURN_OR_MOVE = Pattern.compile(RETURN_LIKE + "|(?<![0-9.])[0-9]+" + SP + "?%(?!p)");
    private static final Pattern ANY_PERCENT = Pattern.compile("(?<![0-9.])[+-]?" + NUM + SP + "?%p?");
    private static final Pattern TURN_LABEL = Pattern.compile(
            "[0-9]+" + SP + "*년차(?:" + SP + "*[0-9]+" + SP + "*월)?|[0-9]+" + SP + "*턴|[0-9]+" + SP + "*번째" + SP + "*턴"
                    + "|(?<![a-z])turn" + SP + "*[0-9]+|(?<![가-힣])턴" + SP + "*[0-9]+");

    private static final List<Pattern> PRICE_PATTERNS = compile(
            "[0-9][0-9,.]*" + SP + "*(?:[만억천조]" + SP + "*(?:[0-9][0-9,.]*" + SP + "*)?)*"
                    + "(?:원(?!칙|인|금|천|래|리|본|가|하)|달러|불(?![가-힣]))",
            "[₩$€£¥]" + SP + "*[0-9]",
            "[0-9][0-9,.]*" + SP + "*(?:krw|usd|dollars?)(?![a-z])",
            "(?<![a-z])(?:krw|usd)" + SP + "*[0-9]",
            "[0-9][0-9,.]*" + SP + "*(?:엔(?![진터지딩트])|유로(?!존|화)|위안|euros?|yen|yuan|eur|jpy|cny|gbp"
                    + "|pounds?)(?![a-z])",
            "[0-9]{3,}" + SP + "*(?:포인트|pts?|points?)(?![a-z])",
            "(?:코스피|코스닥|지수|s&p|나스닥|다우|dow|nasdaq)" + SP + "*[0-9]{4,}",
            "배럴당" + SP + "*[0-9]",
            "(?<![0-9])[0-9]{1,3}(?:,[0-9]{3})+(?![0-9])");

    private static final Pattern ABBREVIATION_DOT = Pattern.compile(
            "(?<![a-z])(jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\.");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[!?。\\n]|(?<![0-9])\\.|\\.(?![0-9])");
    private static final String BLANK = " \t\r\n";
    private static final Pattern LINE_BREAKS = Pattern.compile("\r\n|[\n\r\u000b\u000c\u001c\u001d\u001e\u0085  ]");

    private final Terms terms;

    public MemoryLeakFilter(Terms terms) {
        this.terms = terms;
    }

    public Terms terms() {
        return terms;
    }

    private static List<Pattern> compile(String... patterns) {
        List<Pattern> out = new ArrayList<>();
        for (String p : patterns) {
            out.add(Pattern.compile(p));
        }
        return List.copyOf(out);
    }

    /** Reads the terms file bytes (UTF-8, a BOM is ignored) and the extra titles (month_event titles, corpus titles). */
    public static Terms loadTerms(byte[] raw, Collection<String> titles) {
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        Set<String> entries = new LinkedHashSet<>();
        for (String line : LINE_BREAKS.split(text, -1)) {
            String entry = stripUnicodeSpace(line);
            if (!entry.isEmpty() && !entry.startsWith("#")) {
                String key = compact(entry);
                if (!key.isEmpty()) {
                    entries.add(key);
                }
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String title : titles) {
            String key = compact(title == null ? "" : title);
            if (key.codePointCount(0, key.length()) >= TITLE_MIN) {
                keys.add(key);
            }
        }
        return new Terms(List.copyOf(entries), List.copyOf(keys), "mf1-" + sha256Hex(raw).substring(0, 12));
    }

    /** Trims like Python str.strip(): Java's strip() keeps NBSP and similar no-break spaces, which web pastes bring. */
    private static String stripUnicodeSpace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static String sha256Hex(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** NFKC, lower case, format (Cf) characters removed, Nd digits folded to 0-9, dashes to '-'. */
    static String normalize(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(folded.length());
        folded.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type == Character.FORMAT) {
                return;
            }
            if (type == Character.DECIMAL_DIGIT_NUMBER) {
                out.append((char) ('0' + Character.digit(cp, 10)));
            } else if (cp == '−' || cp == '–' || cp == '‒') {
                out.append('-');
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    /** A letter (general category L*) or an ASCII digit; the same predicate as the Python side. */
    private static boolean isAlnum(int cp) {
        return Character.isLetter(cp) || (cp >= '0' && cp <= '9');
    }

    /** Letters and digits only, for dictionary matching that ignores spacing and punctuation. */
    static String compact(String text) {
        StringBuilder out = new StringBuilder();
        normalize(text).codePoints().filter(MemoryLeakFilter::isAlnum).forEach(out::appendCodePoint);
        return out.toString();
    }

    static List<String> splitSentences(String text) {
        String cleaned = ABBREVIATION_DOT.matcher(normalize(text)).replaceAll("$1");
        List<String> out = new ArrayList<>();
        for (String part : SENTENCE_SPLIT.split(cleaned, -1)) {
            if (!blank(part)) {
                out.add(part);
            }
        }
        return out;
    }

    private static boolean blank(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (BLANK.indexOf(s.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Compact text with, per UTF-16 unit of it, "starts a word" and "the next raw character is not a-z". */
    private record Squeezed(String text, boolean[] starts, boolean[] clear) {
    }

    private static Squeezed squeeze(String sentence) {
        StringBuilder chars = new StringBuilder();
        List<Boolean> starts = new ArrayList<>();
        List<Boolean> clear = new ArrayList<>();
        boolean previousKept = false;
        int index = 0;
        while (index < sentence.length()) {
            int cp = sentence.codePointAt(index);
            int width = Character.charCount(cp);
            boolean kept = isAlnum(cp);
            if (kept) {
                int next = index + width;
                char following = next < sentence.length() ? sentence.charAt(next) : 0;
                boolean notLatin = !(following >= 'a' && following <= 'z');
                for (int unit = 0; unit < width; unit++) {
                    starts.add(unit == 0 && !previousKept);
                    clear.add(notLatin);
                }
                chars.appendCodePoint(cp);
            }
            previousKept = kept;
            index += width;
        }
        boolean[] s = new boolean[starts.size()];
        boolean[] c = new boolean[clear.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = starts.get(i);
            c[i] = clear.get(i);
        }
        return new Squeezed(chars.toString(), s, c);
    }

    /** A term matches from a word start; a term ending in a Latin letter also needs a word end. */
    private static boolean termIn(String term, Squeezed sq) {
        char last = term.charAt(term.length() - 1);
        boolean latinEnd = last >= 'a' && last <= 'z';
        int position = sq.text().indexOf(term);
        while (position >= 0) {
            if (sq.starts()[position] && (!latinEnd || sq.clear()[position + term.length() - 1])) {
                return true;
            }
            position = sq.text().indexOf(term, position + 1);
        }
        return false;
    }

    private static boolean any(List<Pattern> patterns, String sentence) {
        for (Pattern p : patterns) {
            if (p.matcher(sentence).find()) {
                return true;
            }
        }
        return false;
    }

    private static int count(Pattern pattern, String sentence) {
        Matcher m = pattern.matcher(sentence);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Codes of one normalised sentence, FINGERPRINT excluded (it counts per string). */
    private Set<Code> sentenceCodes(String sentence) {
        Set<Code> found = new LinkedHashSet<>();
        if (any(YEAR_MONTH_PATTERNS, sentence)) {
            found.add(Code.YEAR);
            found.add(Code.YEAR_MONTH);
        }
        if (any(YEAR_PATTERNS, sentence)) {
            found.add(Code.YEAR);
        }
        Squeezed sq = squeeze(sentence);
        boolean eventTerm = false;
        for (String term : terms.terms()) {
            if (termIn(term, sq)) {
                eventTerm = true;
                break;
            }
        }
        if (!eventTerm) {
            for (String title : terms.titles()) {
                if (sq.text().contains(title)) {
                    eventTerm = true;
                    break;
                }
            }
        }
        if (eventTerm) {
            found.add(Code.EVENT_TERM);
        }
        if (TURN_LABEL.matcher(sentence).find()
                && (ANY_PERCENT.matcher(sentence).find() || RETURN_LIKE_P.matcher(sentence).find())) {
            found.add(Code.TURN_RETURN);
        }
        if (any(PRICE_PATTERNS, sentence)) {
            found.add(Code.PRICE);
        }
        return found;
    }

    /**
     * Hits of one string. FINGERPRINT is counted over the whole string and reported once, at the sentence where the
     * running count of return-like numbers reaches the threshold.
     */
    List<Hit> scanText(String text, String pointer) {
        List<Hit> hits = new ArrayList<>();
        int returns = 0;
        boolean reported = false;
        int index = 0;
        for (String sentence : splitSentences(text)) {
            index++;
            Set<Code> found = sentenceCodes(sentence);
            Pattern counter = DIRECTION.matcher(sentence).find() ? RETURN_OR_MOVE : RETURN_LIKE_P;
            returns += count(counter, sentence);
            if (returns >= FINGERPRINT_MIN && !reported) {
                found.add(Code.FINGERPRINT);
                reported = true;
            }
            for (Code code : Code.values()) {
                if (found.contains(code)) {
                    hits.add(new Hit(pointer + "#s" + index, code));
                }
            }
        }
        return hits;
    }

    /** Spring and the filter agree on the text that is stored: NFC, then measure. */
    public static String nfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    private record Str(String pointer, String text) {
    }

    private static void collect(List<Str> out, String pointerPrefix, JsonNode array, String field) {
        if (array == null || !array.isArray()) {
            return;
        }
        int index = 0;
        for (JsonNode item : array) {
            JsonNode node = field == null ? item : item.path(field);
            if (node.isString()) {
                out.add(new Str(pointerPrefix + "/" + index + (field == null ? "" : "/" + field), nfc(node.asString())));
            }
            index++;
        }
    }

    private Verdict verdict(List<Str> strings, int cap) {
        List<Hit> hits = new ArrayList<>();
        int total = 0;
        for (Str s : strings) {
            hits.addAll(scanText(s.text(), s.pointer()));
            total += s.text().codePointCount(0, s.text().length());
        }
        return new Verdict(hits, total > cap);
    }

    /** A {@code season-reflection/1} body: good, mistakes[*].text, ruleCandidates, each by index. */
    public Verdict checkReflection(JsonNode doc) {
        List<Str> strings = new ArrayList<>();
        collect(strings, "/good", doc.path("good"), null);
        collect(strings, "/mistakes", doc.path("mistakes"), "text");
        collect(strings, "/ruleCandidates", doc.path("ruleCandidates"), null);
        return verdict(strings, REFLECTION_TOTAL_MAX);
    }

    /** An {@code operating-note/1} body: rules by index. */
    public Verdict checkNote(JsonNode doc) {
        List<Str> strings = new ArrayList<>();
        collect(strings, "/rules", doc.path("rules"), null);
        return verdict(strings, NOTE_TOTAL_MAX);
    }
}
