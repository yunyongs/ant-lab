package antlab.leaderboard;

import antlab.member.Member;
import antlab.member.MemberRepository;
import antlab.scoring.MarketBaselineSeries;
import antlab.scoring.ScoreAssembler;
import antlab.scoring.SeasonReportService;
import antlab.scoring.SeasonReportSources;
import antlab.scoring.SeasonScore;
import antlab.scoring.SeasonScoreRepository;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one finished season for its manager parts in a single read-only {@code REPEATABLE_READ} snapshot: the season,
 * its member kind, its stored score, the report sources (turn totals and benchmark prices) and the path months. It holds
 * no lock, writes nothing and ends before the engine is called.
 */
@Component
public class ManagerPartsReader {

    private static final String MONTHS = """
            SELECT mb.month_start
            FROM season_path sp
            JOIN month_block mb ON mb.id = sp.month_block_id
            WHERE sp.season_id = :season
            ORDER BY sp.turn_no""";

    private final SeasonRepository seasons;
    private final MemberRepository members;
    private final SeasonScoreRepository scores;
    private final SeasonReportService reports;
    private final MarketBaselineSeries baseline;
    private final JdbcClient jdbc;

    public ManagerPartsReader(SeasonRepository seasons, MemberRepository members, SeasonScoreRepository scores,
            SeasonReportService reports, MarketBaselineSeries baseline, JdbcClient jdbc) {
        this.seasons = seasons;
        this.members = members;
        this.scores = scores;
        this.reports = reports;
        this.baseline = baseline;
        this.jdbc = jdbc;
    }

    /** The season's basis; empty while the season has no score row yet (the parts need its process part). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<ManagerPartsBasis> read(long seasonId) {
        Season season = seasons.findById(seasonId).orElseThrow(() -> new IllegalStateException("manager_parts.season_missing"));
        if (season.getStatus() != SeasonStatus.FINISHED) {
            throw new IllegalStateException("manager_parts.season_not_finished");
        }
        Optional<SeasonScore> stored = scores.findBySeasonIdAndMemberId(seasonId, season.getMemberId());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        SeasonScore s = stored.get();
        Member member = members.findById(season.getMemberId())
                .orElseThrow(() -> new IllegalStateException("manager_parts.member_missing"));
        SeasonReportSources sources = reports.sources(season);
        List<LocalDate> months = jdbc.sql(MONTHS).param("season", seasonId).query(LocalDate.class).list();
        ManagerPartsBasis.Score score = new ManagerPartsBasis.Score(s.getSharpe(), s.getBenchSharpe(), s.getRiskScore(),
                s.getProcessScore(), s.getScoreVersion(), s.getRatingExclusion());
        return Optional.of(new ManagerPartsBasis(seasonId, season.getMemberId(), member.getKind(), season.getBaseDate(),
                sources.season(), months, sources.turns().stream().map(SeasonReportSources.TurnFact::totalValue).toList(),
                ScoreAssembler.teamReturns(sources), baseline.returns(sources), score));
    }
}
