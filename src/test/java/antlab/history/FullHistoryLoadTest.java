package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FlywayScratchDatabase;
import antlab.support.PsqlScriptRunner;
import antlab.support.SharedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * db/30_load_full.sql and db/30_load_full_check.sql on the 06-A build fixture (8 assets x 8 months, no price data of
 * its own), in a scratch database migrated by the production Flyway scripts. The fixture is adjusted in memory so that it
 * satisfies every rule (A2: no proxy month after the first ACTUAL month; R2: names for assets with proxy months).
 */
class FullHistoryLoadTest {

    private static final Path FIXTURE = Path.of("tools/fetch/tests/fixtures/build_full");
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final Map<String, String> CHECK_VARS = Map.of("first_month", "2006-09-01", "exp_months", "8", "exp_assets", "8");
    private static final List<String> TABLES = List.of("asset_group", "asset", "month_block", "asset_position", "asset_month_return", "fx_month");

    private static PostgreSQLContainer container;
    private static PsqlScriptRunner runner;

    @BeforeAll
    static void start() throws IOException {
        container = SharedPostgres.container();
        runner = new PsqlScriptRunner(container);
        for (String f : List.of("db/30_load_full.sql", "db/30_load_full_check.sql")) {
            runner.putFile(f, Path.of(f).toAbsolutePath());
        }
    }

    // ---- fixture ----

    private static String read(String relative) throws IOException {
        return Files.readString(FIXTURE.resolve(relative), StandardCharsets.UTF_8);
    }

    /** The six load files plus lineage.csv and proxy_series.csv, keyed by file name. */
    private static Map<String, String> goodFiles() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("month_block.csv", read("expected/month_block.csv"));
        files.put("fx_month.csv", read("expected/fx_month.csv"));
        files.put("asset_position.csv", read("expected/asset_position.csv"));
        files.put("proxy_series.csv", read("map/proxy_series.csv"));
        files.put("asset_group.csv", "code,name,kind\nBOND,Bond,ASSET_CLASS\nFIN,Financials,SECTOR\nGOLD,Gold,ASSET_CLASS\n"
                + "IDX,Index,ASSET_CLASS\nMIX,Mixed,ASSET_CLASS\nTECH,Tech,SECTOR\n");
        // TECH1 2006-09 becomes a proxy month, so its proxy run is not interior (A2)
        files.put("asset_month_return.csv", read("expected/asset_month_return.csv").lines()
                .map(l -> l.startsWith("US,TECH1,2006-09-01,") ? l.substring(0, l.lastIndexOf(',') + 1) + "PROXY_INDEX" : l)
                .reduce("", (a, b) -> a + b + "\n"));
        files.put("lineage.csv", read("expected/lineage.csv").lines()
                .map(l -> l.startsWith("US,TECH1,2006-09-01,") ? "US,TECH1,2006-09-01,PROXY_INDEX,ASSET_US_SPY" : l)
                .reduce("", (a, b) -> a + b + "\n"));
        // R2: every asset with a proxy month gets a generic name
        files.put("asset.csv", read("map/asset.csv").lines()
                .map(l -> l.matches("^(KR,100002|KR,200001|KR,200002|KR,200003|US,MIXE|US,TECH1),.*,$") ? l + "Generic proxy name" : l)
                .reduce("", (a, b) -> a + b + "\n"));
        return files;
    }

    private static void stage(Map<String, String> files) {
        files.forEach((name, content) -> runner.putFile("db/full/" + name, content.getBytes(StandardCharsets.UTF_8)));
    }

    private static PsqlScriptRunner.Result load(FlywayScratchDatabase db) throws Exception {
        return runner.runFile(db.name(), "db/30_load_full.sql", TIMEOUT);
    }

    private static PsqlScriptRunner.Result check(FlywayScratchDatabase db) throws Exception {
        return runner.runFile(db.name(), "db/30_load_full_check.sql", TIMEOUT, CHECK_VARS);
    }

    private static long codeRows(String stdout, String code) {
        return stdout.lines().filter(l -> l.startsWith(code + "|")).count();
    }

    private static String snapshot(JdbcTemplate jdbc) {
        StringBuilder sb = new StringBuilder();
        for (String t : TABLES) {
            sb.append(t).append('\n').append(jdbc.queryForObject(
                    "SELECT coalesce(string_agg(x::text, E'\\n' ORDER BY x.id), '') FROM " + t + " x", String.class)).append('\n');
        }
        return sb.toString();
    }

    private static FlywayScratchDatabase migrated(String prefix) {
        FlywayScratchDatabase db = new FlywayScratchDatabase(container, prefix);
        db.migrateTo("latest");
        return db;
    }

    // ---- load ----

    @Test
    void loadsOnceThenSecondRunChangesNothingAndTheCheckPasses() throws Exception {
        stage(goodFiles());
        try (FlywayScratchDatabase db = migrated("fl_a")) {
            JdbcTemplate jdbc = new JdbcTemplate(db.dataSource());
            PsqlScriptRunner.Result first = load(db);
            assertThat(first.exitCode()).as(first.stderr()).isZero();
            assertThat(first.stdout()).contains("asset_group|6|6", "asset|8|8", "month_block|8|8", "asset_position|8|8",
                    "asset_month_return|64|64", "fx_month|8|8");
            String before = snapshot(jdbc);

            PsqlScriptRunner.Result second = load(db);
            assertThat(second.exitCode()).as(second.stderr()).isZero();
            assertThat(second.stdout()).contains("asset_group|6|0", "asset|8|0", "month_block|8|0", "asset_position|8|0",
                    "asset_month_return|64|0", "fx_month|8|0");
            assertThat(snapshot(jdbc)).as("every row, id and created_at unchanged").isEqualTo(before);

            PsqlScriptRunner.Result checked = check(db);
            assertThat(checked.exitCode()).as(checked.stdout() + checked.stderr()).isZero();
            assertThat(checked.stdout()).isEqualTo("code|detail\nload_full_check\nPASS\n");
        }
    }

    @Test
    void refusesAnExistingRowWithAnotherValueAndLeavesTheDatabaseAsItWas() throws Exception {
        stage(goodFiles());
        try (FlywayScratchDatabase db = migrated("fl_b")) {
            JdbcTemplate jdbc = new JdbcTemplate(db.dataSource());
            assertThat(load(db).exitCode()).isZero();
            jdbc.update("UPDATE asset_month_return SET local_return = local_return + 0.00000001 WHERE id = (SELECT min(id) FROM asset_month_return)");
            String before = snapshot(jdbc);

            PsqlScriptRunner.Result again = load(db);
            assertThat(again.exitCode()).isNotZero();
            assertThat(again.stderr()).contains("refused").contains("asset_month_return differs from the file");
            assertThat(snapshot(jdbc)).isEqualTo(before);
        }
    }

    @Test
    void refusesAMonthOrAssetThatNoFileNames() throws Exception {
        stage(goodFiles());
        try (FlywayScratchDatabase db = migrated("fl_c")) {
            JdbcTemplate jdbc = new JdbcTemplate(db.dataSource());
            assertThat(load(db).exitCode()).isZero();
            jdbc.update("INSERT INTO month_block (month_start) VALUES (DATE '2001-01-01')");

            PsqlScriptRunner.Result again = load(db);
            assertThat(again.exitCode()).isNotZero();
            assertThat(again.stderr()).contains("database month_block not in month_block.csv: 2001-01-01");
        }
    }

    @Test
    void aSampleStyleDatabaseWithOtherValuesIsRefusedWithoutAnyChange() throws Exception {
        stage(goodFiles());
        try (FlywayScratchDatabase db = migrated("fl_d")) {
            JdbcTemplate jdbc = new JdbcTemplate(db.dataSource());
            jdbc.update("INSERT INTO asset_group (code, name, kind) VALUES ('FIN', 'Financials', 'SECTOR')");
            jdbc.update("INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis, proxy_index_name) "
                    + "SELECT 'KR', '100002', 'Fake stock B', 'STOCK', 'KRW', id, DATE '2000-01-03', 'PRICE', 'Another proxy name' FROM asset_group WHERE code = 'FIN'");
            String before = snapshot(jdbc);

            PsqlScriptRunner.Result r = load(db);
            assertThat(r.exitCode()).isNotZero();
            assertThat(r.stderr()).contains("asset differs from the file: KR:100002");
            assertThat(snapshot(jdbc)).isEqualTo(before);
        }
    }

    @ParameterizedTest(name = "input rejected: {0}")
    @MethodSource("badInputs")
    void badInputIsRejectedBeforeTheDatabaseIsTouched(String name, String file, String from, String to, String message) throws Exception {
        Map<String, String> files = goodFiles();
        assertThat(files.get(file)).contains(from);
        files.put(file, files.get(file).replace(from, to));
        stage(files);
        try (FlywayScratchDatabase db = migrated("fl_e")) {
            JdbcTemplate jdbc = new JdbcTemplate(db.dataSource());
            PsqlScriptRunner.Result r = load(db);
            assertThat(r.exitCode()).isNotZero();
            assertThat(r.stderr()).contains(message);
            assertThat(jdbc.queryForObject("SELECT (SELECT count(*) FROM asset) + (SELECT count(*) FROM month_block)", Integer.class)).isZero();
        }
    }

    static Stream<Arguments> badInputs() {
        return Stream.of(
                Arguments.of("seven-decimal return", "asset_month_return.csv", "KR,100001,2006-09-01,-0.01667275", "KR,100001,2006-09-01,-0.0166727", "asset_month_return format"),
                Arguments.of("unknown return kind", "asset_month_return.csv", "KR,100001,2006-09-01,-0.01667275,ACTUAL", "KR,100001,2006-09-01,-0.01667275,GUESS", "asset_month_return format"),
                Arguments.of("month not first of month", "month_block.csv", "2006-10-01", "2006-10-02", "month_block format"),
                Arguments.of("return for an unknown month", "asset_month_return.csv", "KR,100001,2006-10-01", "KR,100001,2001-10-01", "month is not in month_block.csv"),
                Arguments.of("duplicate month", "month_block.csv", "2006-10-01", "2006-09-01", "month_block duplicate key"),
                Arguments.of("price with three decimals", "asset_position.csv", "KR,100001,2007-05-02,35.0946", "KR,100001,2007-05-02,35.094", "asset_position format"));
    }

    @ParameterizedTest(name = "refused: {0}")
    @MethodSource("refusals")
    void everyRefusalBranchStopsAndLeavesTheDatabaseAsItWas(String message, String sql) throws Exception {
        stage(goodFiles());
        try (FlywayScratchDatabase db = migrated("fl_r")) {
            JdbcTemplate jdbc = new JdbcTemplate(db.dataSource());
            assertThat(load(db).exitCode()).isZero();
            for (String statement : sql.split(";")) {
                jdbc.update(statement);
            }
            String before = snapshot(jdbc);
            PsqlScriptRunner.Result again = load(db);
            assertThat(again.exitCode()).isNotZero();
            assertThat(again.stderr()).contains(message);
            assertThat(snapshot(jdbc)).isEqualTo(before);
        }
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("asset_group differs from the file: FIN", "UPDATE asset_group SET name = 'Other' WHERE code = 'FIN'"),
                Arguments.of("asset_position differs from the file: KR:100001", "UPDATE asset_position SET base_price = base_price + 1 WHERE asset_id = (SELECT id FROM asset WHERE ticker = '100001')"),
                Arguments.of("fx_month differs from the file", "UPDATE fx_month SET fx_change = fx_change + 0.00000001 WHERE id = (SELECT min(id) FROM fx_month)"),
                Arguments.of("database asset not in asset.csv: KR:999999", "INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis) "
                        + "SELECT 'KR', '999999', 'extra', 'STOCK', 'KRW', min(id), DATE '2000-01-03', 'PRICE' FROM asset_group"),
                Arguments.of("partial earlier load", "DELETE FROM fx_month WHERE id = (SELECT min(id) FROM fx_month)"));
    }

    @Test
    void aHeaderOnlyFileIsRejected() throws Exception {
        Map<String, String> files = goodFiles();
        files.put("month_block.csv", "month_start\n");
        stage(files);
        try (FlywayScratchDatabase db = migrated("fl_h")) {
            PsqlScriptRunner.Result r = load(db);
            assertThat(r.exitCode()).isNotZero();
            assertThat(r.stderr()).contains("month_block.csv has no data rows");
            assertThat(new JdbcTemplate(db.dataSource()).queryForObject("SELECT count(*) FROM asset", Integer.class)).isZero();
        }
    }

    @Test
    void aMissingFileCommitsNothingWhetherOrNotOnErrorStopIsSet() throws Exception {
        stage(goodFiles());
        container.execInContainer("rm", "-f", "/work/db/full/asset_month_return.csv");
        try (FlywayScratchDatabase db = migrated("fl_m")) {
            JdbcTemplate jdbc = new JdbcTemplate(db.dataSource());
            assertThat(load(db).exitCode()).isNotZero();
            PsqlScriptRunner.Result off = runner.runFile(db.name(), "db/30_load_full.sql", TIMEOUT, Map.of("ON_ERROR_STOP", "0"));
            assertThat(off.exitCode()).as("the script refuses to run without ON_ERROR_STOP").isNotZero();
            assertThat(off.stderr() + off.stdout()).contains("ON_ERROR_STOP");
            assertThat(jdbc.queryForObject("SELECT (SELECT count(*) FROM asset) + (SELECT count(*) FROM month_block) + (SELECT count(*) FROM asset_group)", Integer.class)).isZero();
        }
    }

    @Test
    void theCheckNeverPrintsPassWithoutOnErrorStopOrWithAViolation() throws Exception {
        stage(goodFiles());
        try (FlywayScratchDatabase db = migrated("fl_g")) {
            assertThat(load(db).exitCode()).isZero();
            PsqlScriptRunner.Result clean = runner.runFile(db.name(), "db/30_load_full_check.sql", TIMEOUT,
                    Map.of("ON_ERROR_STOP", "0", "first_month", "2006-09-01", "exp_months", "8", "exp_assets", "8"));
            assertThat(clean.exitCode()).as("even a clean database: the guard refuses").isNotZero();
            assertThat(clean.stdout()).doesNotContain("PASS");

            new JdbcTemplate(db.dataSource()).update("DELETE FROM fx_month WHERE id = (SELECT min(id) FROM fx_month)");
            PsqlScriptRunner.Result bad = runner.runFile(db.name(), "db/30_load_full_check.sql", TIMEOUT,
                    Map.of("ON_ERROR_STOP", "0", "first_month", "2006-09-01", "exp_months", "8", "exp_assets", "8"));
            assertThat(bad.exitCode()).isNotZero();
            assertThat(bad.stdout()).doesNotContain("PASS");
        }
    }

    // ---- check: one planted violation per code gives exactly one row of that code ----

    /** A case: where to plant (SQL on the loaded database, or an edit of the staged lineage.csv) and which code must show one row. */
    record Plant(String code, String sql, String lineageFrom, String lineageTo) {
        static Plant db(String code, String sql) {
            return new Plant(code, sql, null, null);
        }

        static Plant lineage(String code, String from, String to) {
            return new Plant(code, null, from, to);
        }
    }

    static Stream<Plant> plants() {
        String lastMonth = "(SELECT id FROM month_block ORDER BY month_start DESC LIMIT 1)";
        return Stream.of(
                Plant.db("CNT_MONTH", "DELETE FROM fx_month WHERE month_block_id = " + lastMonth + ";"
                        + "DELETE FROM asset_month_return WHERE month_block_id = " + lastMonth + ";"
                        + "DELETE FROM month_block WHERE id = " + lastMonth),
                Plant.db("CAL_MISSING", "DELETE FROM fx_month WHERE month_block_id = " + lastMonth + ";"
                        + "DELETE FROM asset_month_return WHERE month_block_id = " + lastMonth + ";"
                        + "DELETE FROM month_block WHERE id = " + lastMonth),
                Plant.db("CAL_EXTRA", "INSERT INTO month_block (month_start) VALUES (DATE '2001-01-01')"),
                Plant.db("CNT_ASSET", "INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis) "
                        + "SELECT 'KR', '999999', 'extra', 'STOCK', 'KRW', min(id), DATE '2000-01-03', 'PRICE' FROM asset_group"),
                Plant.db("CNT_RETURN", "DELETE FROM asset_month_return WHERE id = (SELECT min(id) FROM asset_month_return)"),
                Plant.db("CNT_FX", "DELETE FROM fx_month WHERE id = (SELECT min(id) FROM fx_month)"),
                Plant.db("CNT_POS", "INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate) "
                        + "SELECT id, DATE '2007-05-03', 1.0000, 1.0000 FROM asset WHERE ticker = '100001'"),
                Plant.db("POS_BASE_DATE", "INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate) "
                        + "SELECT id, DATE '2007-05-03', 1.0000, 1.0000 FROM asset WHERE ticker = '100001'"),
                Plant.db("POS_MISSING", "DELETE FROM asset_position WHERE asset_id = (SELECT id FROM asset WHERE ticker = '100001')"),
                Plant.db("FMT_TICKER", "UPDATE asset SET ticker = 'SPY ' WHERE ticker = 'SPY'"),
                Plant.db("ETF_RANK", "UPDATE asset_position SET market_cap_rank = 1 WHERE asset_id = (SELECT id FROM asset WHERE ticker = '200001')"),
                Plant.db("H1", "UPDATE asset_position SET base_fx_rate = base_fx_rate + 1 WHERE asset_id = (SELECT id FROM asset WHERE ticker = 'TECH1')"),
                Plant.db("H3", "DELETE FROM asset_month_return WHERE id = (SELECT min(id) FROM asset_month_return)"),
                Plant.db("H4", "DELETE FROM fx_month WHERE id = (SELECT min(id) FROM fx_month)"),
                Plant.lineage("H7_LINEAGE_MISSING", "KR,100001,2006-10-01,ACTUAL,\n", ""),
                Plant.lineage("H7_LINEAGE_DUP", "KR,100001,2006-10-01,ACTUAL,\n", "KR,100001,2006-10-01,ACTUAL,\nKR,100001,2006-10-01,ACTUAL,\n"),
                Plant.lineage("H7_LINEAGE_ORPHAN", "KR,100001,2006-10-01,ACTUAL,\n", "KR,100001,2006-10-01,ACTUAL,\nKR,100001,2001-10-01,ACTUAL,\n"),
                Plant.db("H7_KIND", "UPDATE asset_month_return SET return_kind = 'PROXY_INDEX' WHERE id = ("
                        + "SELECT r.id FROM asset_month_return r JOIN asset a ON a.id = r.asset_id JOIN month_block m ON m.id = r.month_block_id "
                        + "WHERE a.ticker = '100001' AND m.month_start = DATE '2006-09-01')"),
                Plant.lineage("H7_PROXY_SERIES", "KR,100002,2006-09-01,PROXY_INDEX,KR_KOSPI\n", "KR,100002,2006-09-01,PROXY_INDEX,\n"),
                Plant.lineage("H7_PROXY_SERIES", "KR,100002,2006-09-01,PROXY_INDEX,KR_KOSPI\n", "KR,100002,2006-09-01,PROXY_INDEX,NO_SUCH_SERIES\n"),
                Plant.lineage("H7_ACTUAL_SERIES", "KR,100001,2006-10-01,ACTUAL,\n", "KR,100001,2006-10-01,ACTUAL,KR_KOSPI\n"),
                Plant.db("H7_STOCK", "UPDATE asset_month_return SET return_kind = 'PROXY_INDEX' WHERE id = ("
                        + "SELECT r.id FROM asset_month_return r JOIN asset a ON a.id = r.asset_id JOIN month_block m ON m.id = r.month_block_id "
                        + "WHERE a.ticker = '100001' AND m.month_start = DATE '2007-01-01')"),
                Plant.db("H7_ETF_LISTING", "UPDATE asset SET listed_on = DATE '2007-01-15' WHERE ticker = '200001'"),
                Plant.db("H7_BASIS", "UPDATE asset SET return_basis = 'TOTAL' WHERE ticker = '100001'"),
                Plant.db("A2", "UPDATE asset_month_return SET return_kind = 'PROXY_INDEX' WHERE id = ("
                        + "SELECT r.id FROM asset_month_return r JOIN asset a ON a.id = r.asset_id JOIN month_block m ON m.id = r.month_block_id "
                        + "WHERE a.ticker = '100001' AND m.month_start = DATE '2006-10-01')"),
                Plant.db("R2_NAME_MISSING", "UPDATE asset SET proxy_index_name = NULL WHERE ticker = '100002'"),
                Plant.db("R2_NAME_EXTRA", "UPDATE asset SET proxy_index_name = 'Some name' WHERE ticker = '100001'"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("plants")
    void aPlantedViolationGivesExactlyOneRowOfItsCode(Plant plant) throws Exception {
        Map<String, String> files = goodFiles();
        stage(files);
        try (FlywayScratchDatabase db = migrated("fl_p")) {
            assertThat(load(db).exitCode()).isZero();
            assertThat(check(db).exitCode()).as("the unmodified fixture passes").isZero();

            if (plant.sql() != null) {
                for (String statement : plant.sql().split(";")) {
                    new JdbcTemplate(db.dataSource()).update(statement);
                }
            } else {
                String lineage = files.get("lineage.csv");
                assertThat(lineage).contains(plant.lineageFrom());
                runner.putFile("db/full/lineage.csv", lineage.replace(plant.lineageFrom(), plant.lineageTo()).getBytes(StandardCharsets.UTF_8));
            }
            PsqlScriptRunner.Result r = check(db);
            assertThat(r.exitCode()).as("a violation ends the script with an error").isNotZero();
            List<String> rows = new ArrayList<>(r.stdout().lines().filter(l -> l.startsWith(plant.code() + "|")).toList());
            assertThat(rows).as(plant.code() + " rows in:\n" + r.stdout()).hasSize(1);
            assertThat(r.stdout()).doesNotContain("PASS");
        }
    }
}
