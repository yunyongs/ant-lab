package antlab.collect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Committed data for the collector job tests (the job commits per target, so the test cannot sit in a rolled-back
 * transaction): the asset rows of the tracked sample CSVs, and the cleanup of everything the run wrote.
 */
public final class CollectTestData {

    /** Rules C1-C6 of erd 10.5: each query returns no row on consistent data. */
    public static final List<String> RULES = List.of(CollectedDataRulesTest.C1, CollectedDataRulesTest.C2,
            CollectedDataRulesTest.C3, CollectedDataRulesTest.C4, CollectedDataRulesTest.C5, CollectedDataRulesTest.C6);

    private final JdbcTemplate jdbc;
    private final List<Long> assetIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();

    public CollectTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Loads db/sample/asset_group.csv and asset.csv (tracked: names and tickers only, no prices). */
    public void seedAssets() {
        for (String[] row : rows("db/sample/asset_group.csv")) {
            groupIds.add(jdbc.queryForObject("INSERT INTO asset_group (code, name, kind) VALUES (?, ?, ?) RETURNING id",
                    Long.class, row[0], row[1], row[2]));
        }
        for (String[] row : rows("db/sample/asset.csv")) {
            assetIds.add(jdbc.queryForObject("""
                    INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis,
                                       proxy_index_name)
                    VALUES (?, ?, ?, ?, ?, (SELECT id FROM asset_group WHERE code = ?), ?::date, ?, ?) RETURNING id
                    """, Long.class, row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7],
                    row[8].isEmpty() ? null : row[8]));
        }
    }

    public void cleanUp() {
        jdbc.update("DELETE FROM asset_daily_price");
        jdbc.update("DELETE FROM series_observation");
        jdbc.update("DELETE FROM series");
        jdbc.update("DELETE FROM job_run");
        assetIds.forEach(id -> jdbc.update("DELETE FROM asset WHERE id = ?", id));
        groupIds.forEach(id -> jdbc.update("DELETE FROM asset_group WHERE id = ?", id));
        assetIds.clear();
        groupIds.clear();
    }

    private static List<String[]> rows(String file) {
        try {
            List<String> lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
            return lines.stream().skip(1).filter(line -> !line.isBlank()).map(line -> line.split(",", -1)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A clock the test moves by hand; never fixed to one instant for good. */
    public static final class TestClock extends Clock {
        private Instant now;

        public TestClock(Instant start) {
            this.now = start;
        }

        public void set(Instant instant) {
            now = instant;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
