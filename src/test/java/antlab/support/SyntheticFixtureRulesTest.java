package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.ReportRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The repository is public, so the synthetic season fixture may hold fake tickers and fake values only. Always on, no Docker.
 * The rules are the ones the fixture's own header promises: a {@code FAKE DATA} first line, tickers {@code ZZ} plus four
 * characters, names that start with {@code FAKE}, and no ticker of the tracked real asset list anywhere in the file.
 *
 * <p>One exception is named here and nowhere else: the season report reads its baseline from the two codes fixed in
 * {@link ReportRules}, so the fixture must hold exactly those two codes, with fake names and invented values.
 */
class SyntheticFixtureRulesTest {

    static final Path FIXTURE = Path.of("src/test/resources/fixtures/synthetic/season_fake.sql");
    static final Path REAL_ASSETS = Path.of("db/sample/asset.csv");
    static final String HEADER = "-- FAKE DATA — not market data";

    /** The only non-ZZ assets allowed: the report's baseline proxies, as "market:ticker". */
    private static final Set<String> BASELINE_PROXIES = Set.of(
            ReportRules.BENCH_KR_MARKET + ":" + ReportRules.BENCH_KR_TICKER,
            ReportRules.BENCH_US_MARKET + ":" + ReportRules.BENCH_US_TICKER);

    private static final Pattern ASSET_ROW = Pattern.compile(
            "^\\s*\\('(KR|US)',\\s*'([^']*)',\\s*'([^']*)',\\s*'(?:STOCK|ETF)',\\s*'(?:KRW|USD)',", Pattern.MULTILINE);
    private static final Pattern GROUP_ROW = Pattern.compile(
            "^\\s*\\('([^']*)',\\s*'([^']*)',\\s*'(?:SECTOR|ASSET_CLASS)'\\)[,;]", Pattern.MULTILINE);
    private static final Pattern KEYED_ROW = Pattern.compile("^\\s*\\('(KR|US)',\\s*'([^']*)'", Pattern.MULTILINE);
    private static final Pattern FAKE_TICKER = Pattern.compile("ZZ[A-Z0-9]{4}");
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    private static String fixture() throws IOException {
        return Files.readString(FIXTURE, StandardCharsets.UTF_8);
    }

    @Test
    void 첫_줄이_가짜_데이터_표지다() throws IOException {
        assertThat(fixture().lines().findFirst().orElse("")).isEqualTo(HEADER);
    }

    @Test
    void 티커는_ZZ_다음_네_글자이고_예외는_기준선_대용_둘뿐이다() throws IOException {
        List<String[]> assets = assets(fixture());
        assertThat(assets).as("asset rows found in the fixture").hasSizeGreaterThanOrEqualTo(10);
        Set<String> exceptions = new HashSet<>();
        for (String[] asset : assets) {
            if (!FAKE_TICKER.matcher(asset[1]).matches()) {
                exceptions.add(asset[0] + ":" + asset[1]);
            }
        }
        assertThat(exceptions).as("non-ZZ tickers are exactly the report's baseline proxies").isEqualTo(BASELINE_PROXIES);
    }

    @Test
    void 종목과_그룹_이름은_FAKE_로_시작한다() throws IOException {
        String sql = fixture();
        for (String[] asset : assets(sql)) {
            assertThat(asset[2]).as("name of " + asset[1]).startsWith("FAKE ");
        }
        Matcher group = GROUP_ROW.matcher(sql);
        int groups = 0;
        while (group.find()) {
            groups++;
            assertThat(group.group(1)).as("group code").startsWith("FAKE_");
            assertThat(group.group(2)).as("group name").startsWith("FAKE ");
        }
        assertThat(groups).as("asset group rows found").isPositive();
    }

    @Test
    void 실제_표본의_종목과_겹치지_않는다() throws IOException {
        Set<String> real = realTickers();
        assertThat(real).as("tracked real asset list").hasSizeGreaterThan(10);
        String sql = fixture();
        Set<String> allowedProxyTickers = Set.of(ReportRules.BENCH_KR_TICKER, ReportRules.BENCH_US_TICKER);
        for (String[] asset : assets(sql)) {
            if (!allowedProxyTickers.contains(asset[1])) {
                assertThat(real).as("fixture ticker " + asset[1] + " is a real ticker").doesNotContain(asset[1]);
            }
        }
        Matcher quoted = QUOTED.matcher(sql);
        while (quoted.find()) {
            String token = quoted.group(1);
            if (!allowedProxyTickers.contains(token)) {
                assertThat(real).as("quoted token '" + token + "' is a real ticker").doesNotContain(token);
            }
        }
    }

    @Test
    void 종목을_가리키는_모든_행은_선언한_종목이다() throws IOException {
        String sql = fixture();
        Set<String> declared = new HashSet<>();
        assets(sql).forEach(a -> declared.add(a[0] + ":" + a[1]));
        Matcher row = KEYED_ROW.matcher(sql);
        int rows = 0;
        while (row.find()) {
            rows++;
            assertThat(declared).as("row for " + row.group(1) + ":" + row.group(2) + " has no asset row")
                    .contains(row.group(1) + ":" + row.group(2));
        }
        assertThat(rows).as("rows keyed by market and ticker").isGreaterThan(declared.size());
    }

    @Test
    void 시장_종류를_모두_갖는다() throws IOException {
        Set<String> markets = new HashSet<>();
        assets(fixture()).forEach(a -> markets.add(a[0]));
        assertThat(markets).containsExactlyInAnyOrder("KR", "US");
    }

    /** Each element is {market, ticker, name}. */
    private static List<String[]> assets(String sql) {
        List<String[]> out = new ArrayList<>();
        Matcher m = ASSET_ROW.matcher(sql);
        while (m.find()) {
            out.add(new String[] {m.group(1), m.group(2), m.group(3)});
        }
        return out;
    }

    private static Set<String> realTickers() throws IOException {
        Set<String> out = new HashSet<>();
        List<String> lines = Files.readAllLines(REAL_ASSETS, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            if (cells.length > 1 && !cells[1].isBlank()) {
                out.add(cells[1].trim());
            }
        }
        return out;
    }
}
