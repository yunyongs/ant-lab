package antlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V17 drops ck_asset_proxy_etf so a stock may carry a proxy index name; every other asset rule stays. */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayV17UpgradeTest {

    private static final String ASSET = """
            INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis,
                proxy_index_name)
            SELECT 'KR', ?, 'x', ?, 'KRW', g.id, '2000-01-03', 'PRICE', ? FROM asset_group g ORDER BY g.id LIMIT 1
            """;

    @Autowired
    PostgreSQLContainer postgres;

    private static void group(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO asset_group (code, name, kind) VALUES ('ZZ_GROUP', 'g', 'SECTOR')");
    }

    @Test
    void upgradeFromV16KeepsAssetRowsAndAllowsStockProxyName() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v17_upgrade")) {
            scratch.migrateTo("16");
            JdbcTemplate jdbc = new JdbcTemplate(scratch.dataSource());
            group(jdbc);
            assertThat(jdbc.update(ASSET, "ETF001", "ETF", "index a")).isEqualTo(1);
            assertThat(jdbc.update(ASSET, "STK001", "STOCK", null)).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update(ASSET, "STK002", "STOCK", "index b"))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_asset_proxy_etf");
            var before = jdbc.queryForList("SELECT * FROM asset ORDER BY id");

            assertThat(scratch.migrateTo("17").migrationsExecuted).isEqualTo(1);

            assertThat(jdbc.queryForList("SELECT * FROM asset ORDER BY id")).isEqualTo(before);
            assertThat(jdbc.update(ASSET, "STK002", "STOCK", "index b")).isEqualTo(1);
            assertThat(jdbc.queryForList(
                    "SELECT conname FROM pg_constraint WHERE conrelid = 'asset'::regclass AND conname = 'ck_asset_proxy_etf'",
                    String.class)).isEmpty();
            assertThatThrownBy(() -> jdbc.update(ASSET, "STK003", "BOND", null))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_asset_asset_type");
        }
    }

    @Test
    void migratingV17AgainExecutesNothing() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v17_again")) {
            scratch.migrateTo("17");

            assertThat(scratch.migrateTo("17").migrationsExecuted).isZero();
        }
    }
}
