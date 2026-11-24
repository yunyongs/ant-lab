package antlab.leaderboard;

import antlab.member.MemberKind;
import antlab.scoring.RatingExclusion;
import antlab.scoring.SeasonReportSources.SeasonFacts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the manager parts of one finished season are built from, read in one snapshot. The series are the stored
 * season facts, unrounded; nothing here is sent to a client.
 *
 * @param months           month starts of the season path in turn order
 * @param totals           turn 1..T end values in won
 * @param returns          team returns for turns 1..T at the score working scale
 * @param benchmarkReturns benchmark returns for turns 1..T on the same base
 * @param score            the stored season score this season's process part and Sharpe come from
 */
record ManagerPartsBasis(long seasonId, long memberId, MemberKind memberKind, LocalDate baseDate, SeasonFacts settings,
        List<LocalDate> months, List<BigDecimal> totals, List<BigDecimal> returns, List<BigDecimal> benchmarkReturns,
        Score score) {

    /** The stored {@code season_score} values the request and the row use (Sharpe at 8 places, items at 2). */
    record Score(BigDecimal sharpe, BigDecimal benchSharpe, BigDecimal riskScore, BigDecimal processScore,
            String scoreVersion, RatingExclusion ratingExclusion) {
    }

    ManagerPartsBasis {
        months = List.copyOf(months);
        totals = List.copyOf(totals);
        returns = List.copyOf(returns);
        benchmarkReturns = List.copyOf(benchmarkReturns);
    }

    @Override
    public String toString() {
        return "ManagerPartsBasis[seasonId=" + seasonId + "]";
    }
}
