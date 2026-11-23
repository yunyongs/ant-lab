package antlab.leaderboard;

import antlab.leaderboard.SeasonManagerParts.Ranked;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Insert-only access to {@code season_manager_parts}: no statement here updates or deletes, and the application role has
 * no right to (afterMigrate class APPEND). A row already stored for the season and reference version stands: a second
 * writer (the job and an administrator run at once) inserts nothing.
 */
@Repository
public class ManagerPartsWriter {

    private static final String INSERT = """
            INSERT INTO season_manager_parts (season_id, member_id, reference_version, exclusion, reference_key,
                reference_size, bot_version, engine_version, excess_cagr, sharpe_diff, sortino_diff, calmar_diff, mdd,
                bench_mdd, pct_excess_cagr, pct_sharpe_diff, pct_sortino_diff, pct_calmar_diff, pct_risk_item, pct_mdd,
                part_return, part_risk_adjusted, part_risk, part_process, process_score_version, final_multiple)
            VALUES (:season, :member, :version, :exclusion, :key, :size, :bot, :engine, :excessCagr, :sharpeDiff,
                :sortinoDiff, :calmarDiff, :mdd, :benchMdd, :pctExcessCagr, :pctSharpeDiff, :pctSortinoDiff, :pctCalmarDiff,
                :pctRiskItem, :pctMdd, :partReturn, :partRiskAdjusted, :partRisk, :partProcess, :processVersion,
                :finalMultiple)
            ON CONFLICT (season_id, reference_version) DO NOTHING""";

    private final JdbcClient jdbc;

    public ManagerPartsWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true when the row was written now, false when one was already stored for the season and version */
    public boolean insert(SeasonManagerParts row) {
        Optional<Ranked> r = Optional.ofNullable(row.ranked());
        return jdbc.sql(INSERT)
                .param("season", row.seasonId())
                .param("member", row.memberId())
                .param("version", row.referenceVersion())
                .param("exclusion", row.exclusion() == null ? null : row.exclusion().name())
                .param("key", r.map(Ranked::referenceKey).orElse(null))
                .param("size", r.map(Ranked::referenceSize).orElse(null))
                .param("bot", r.map(Ranked::botVersion).orElse(null))
                .param("engine", r.map(Ranked::engineVersion).orElse(null))
                .param("excessCagr", r.map(Ranked::excessCagr).orElse(null))
                .param("sharpeDiff", r.map(Ranked::sharpeDiff).orElse(null))
                .param("sortinoDiff", r.map(Ranked::sortinoDiff).orElse(null))
                .param("calmarDiff", r.map(Ranked::calmarDiff).orElse(null))
                .param("mdd", r.map(Ranked::mdd).orElse(null))
                .param("benchMdd", r.map(Ranked::benchMdd).orElse(null))
                .param("pctExcessCagr", r.map(Ranked::pctExcessCagr).orElse(null))
                .param("pctSharpeDiff", r.map(Ranked::pctSharpeDiff).orElse(null))
                .param("pctSortinoDiff", r.map(Ranked::pctSortinoDiff).orElse(null))
                .param("pctCalmarDiff", r.map(Ranked::pctCalmarDiff).orElse(null))
                .param("pctRiskItem", r.map(Ranked::pctRiskItem).orElse(null))
                .param("pctMdd", r.map(Ranked::pctMdd).orElse(null))
                .param("partReturn", r.map(Ranked::partReturn).orElse(null))
                .param("partRiskAdjusted", r.map(Ranked::partRiskAdjusted).orElse(null))
                .param("partRisk", r.map(Ranked::partRisk).orElse(null))
                .param("partProcess", row.partProcess())
                .param("processVersion", row.processScoreVersion())
                .param("finalMultiple", row.finalMultiple())
                .update() == 1;
    }

    /** Whether the season already has a row for the reference version. */
    public boolean exists(long seasonId, String referenceVersion) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM season_manager_parts WHERE season_id = :season AND reference_version = :version)")
                .param("season", seasonId).param("version", referenceVersion).query(Boolean.class).single();
    }
}
