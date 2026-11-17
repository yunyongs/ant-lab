package antlab.support;

import antlab.engine.HistoryDigest;
import antlab.history.ReplayMonth;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Builds the JSON the fake engine answers {@code POST /v1/paths} with, from months a test chose itself. */
public final class EnginePathAnswers {

    private EnginePathAnswers() {
    }

    /** The digest the real engine would report if it read exactly these months of the database. */
    public static String digestOf(String engineVersion, List<ReplayMonth> databaseMonths, LocalDate baseDate) {
        return HistoryDigest.of(engineVersion, databaseMonths.stream().map(ReplayMonth::monthStart).toList(), baseDate);
    }

    public static String body(String engineVersion, List<LocalDate> months, String historyDigest) {
        List<String> blocks = new ArrayList<>();
        for (int start = 0; start < months.size(); start += 6) {
            int length = Math.min(6, months.size() - start);
            blocks.add("{\"startTurn\":%d,\"drawnLength\":%d,\"length\":%d,\"tier\":\"ALIGNED\"}".formatted(start, length, length));
        }
        List<String> texts = months.stream().map(month -> "\"" + YearMonth.from(month) + "\"").toList();
        return "{\"engineVersion\":\"%s\",\"months\":[%s],\"blocks\":[%s],\"overlapMonths\":0,\"overlapMet\":true,\"attempt\":0,\"historyDigest\":\"%s\"}"
                .formatted(engineVersion, String.join(",", texts), String.join(",", blocks), historyDigest);
    }
}
