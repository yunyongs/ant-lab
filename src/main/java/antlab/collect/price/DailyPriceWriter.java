package antlab.collect.price;

import antlab.collect.PriceBasis;
import antlab.collect.PriceSource;
import antlab.collect.job.RunLostException;
import antlab.collect.kis.DailyClose;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Appends closes as vintages (erd D41): a close equal to the latest stored vintage of its date adds nothing, a different
 * one adds the next vintage, a new date adds vintage 1. There is no UPDATE or DELETE on asset_daily_price here, by design.
 * One target is one transaction, so a failing target leaves the others' rows in place.
 */
final class DailyPriceWriter {

    record Result(int inserted, int revised, int unchanged, int lateRevisions) {
    }

    /** FOR SHARE waits for a recovery UPDATE of the run row and blocks it until this transaction ends. */
    private static final String RUN_STATUS = "SELECT status FROM job_run WHERE id = ? FOR SHARE";

    private static final String LATEST_DATE = """
            SELECT max(trade_date) FROM asset_daily_price WHERE asset_id = ? AND price_basis = ?
            """;

    private static final String LATEST_VINTAGES = """
            SELECT p.trade_date, p.close_price, p.vintage_no, p.observed_at
            FROM asset_daily_price p
            JOIN (SELECT trade_date, max(vintage_no) AS vintage_no
                  FROM asset_daily_price
                  WHERE asset_id = ? AND price_basis = ? AND trade_date BETWEEN ? AND ?
                  GROUP BY trade_date) m
              ON m.trade_date = p.trade_date AND m.vintage_no = p.vintage_no
            WHERE p.asset_id = ? AND p.price_basis = ?
            """;

    private static final String INSERT = """
            INSERT INTO asset_daily_price (asset_id, trade_date, price_basis, source, exchange_code, close_price,
                                           vintage_no, observed_at, job_run_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private record Stored(BigDecimal close, int vintageNo, Instant observedAt) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    DailyPriceWriter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    Optional<LocalDate> latestTradeDate(long assetId, PriceBasis basis) {
        return Optional.ofNullable(jdbc.queryForObject(LATEST_DATE, LocalDate.class, assetId, basis.name()));
    }

    /**
     * @param revisedBefore a changed close on a date before this one counts as a late revision (an adjusted series was
     *                      rewritten, so older stored months may sit on another basis)
     */
    Result write(long jobRunId, Instant observedAt, long assetId, PriceBasis basis, String exchangeCode, PriceWindow window,
            LocalDate revisedBefore, List<DailyClose> closes) {
        return tx.execute(status -> {
            List<String> state = jdbc.queryForList(RUN_STATUS, String.class, jobRunId);
            if (state.size() != 1 || !"RUNNING".equals(state.getFirst())) {
                throw new RunLostException(jobRunId);
            }
            Map<LocalDate, Stored> latest = new HashMap<>();
            jdbc.query(LATEST_VINTAGES, rs -> {
                latest.put(rs.getObject("trade_date", LocalDate.class),
                        new Stored(rs.getBigDecimal("close_price"), rs.getInt("vintage_no"),
                                rs.getObject("observed_at", OffsetDateTime.class).toInstant()));
            }, assetId, basis.name(), window.from(), window.to(), assetId, basis.name());

            List<Object[]> rows = new ArrayList<>();
            int revised = 0;
            int unchanged = 0;
            int late = 0;
            for (DailyClose close : closes) {
                if (close.tradeDate().isBefore(window.from()) || close.tradeDate().isAfter(window.to())) {
                    continue;
                }
                Stored known = latest.get(close.tradeDate());
                if (known != null && known.close().compareTo(close.close()) == 0) {
                    unchanged++;
                    continue;
                }
                if (known != null && !observedAt.isAfter(known.observedAt())) {
                    throw new ClockSkewException();
                }
                int vintage = known == null ? 1 : known.vintageNo() + 1;
                if (known != null) {
                    revised++;
                    if (close.tradeDate().isBefore(revisedBefore)) {
                        late++;
                    }
                }
                rows.add(new Object[] {assetId, close.tradeDate(), basis.name(), PriceSource.KIS.name(), exchangeCode,
                        close.close(), vintage, observedAt.atOffset(ZoneOffset.UTC), jobRunId});
            }
            if (!rows.isEmpty()) {
                jdbc.batchUpdate(INSERT, rows);
            }
            return new Result(rows.size(), revised, unchanged, late);
        });
    }
}
