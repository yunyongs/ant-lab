package antlab.engine;

import antlab.history.ReplayMonth;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Digest of the replayable history the engine used (pure). It must equal the engine's {@code historyDigest} of a path
 * answer: SHA-256 (lower-case hex) of the ASCII text {@code engineVersion} + one {@code YYYY-MM} line for every distinct
 * month in [2007-01-01, January 1 of the base year), sorted, joined with a line feed and without a trailing one. Equal
 * digests mean both sides read the same set of months.
 */
public final class HistoryDigest {

    private HistoryDigest() {
    }

    public static String of(String engineVersion, Collection<LocalDate> monthStarts, LocalDate baseDate) {
        return of(engineVersion, monthStarts, baseDate, SimpleBootstrap.REPLAY_START);
    }

    public static String of(String engineVersion, Collection<LocalDate> monthStarts, LocalDate baseDate, LocalDate replayStart) {
        Objects.requireNonNull(replayStart, "replayStart");
        Objects.requireNonNull(engineVersion, "engineVersion");
        Objects.requireNonNull(monthStarts, "monthStarts");
        LocalDate upper = SimpleBootstrap.replayUpperBound(baseDate);
        TreeSet<YearMonth> replayable = new TreeSet<>();
        for (LocalDate start : monthStarts) {
            if (!start.isBefore(replayStart) && start.isBefore(upper)) {
                replayable.add(YearMonth.from(start));
            }
        }
        List<String> lines = new ArrayList<>(replayable.size() + 1);
        lines.add(engineVersion);
        replayable.forEach(month -> lines.add(month.toString()));
        return sha256Hex(String.join("\n", lines));
    }

    /**
     * Labelled variant for {@code m2-sbc6-v1}: the same window, but every line is {@code YYYY-MM:P3} or
     * {@code YYYY-MM:-} for an unlabelled month. Labels of months outside the window are not part of it.
     */
    public static String ofLabelled(String engineVersion, Collection<ReplayMonth> months, LocalDate baseDate,
            LocalDate replayStart) {
        Objects.requireNonNull(replayStart, "replayStart");
        Objects.requireNonNull(engineVersion, "engineVersion");
        Objects.requireNonNull(months, "months");
        LocalDate upper = SimpleBootstrap.replayUpperBound(baseDate);
        TreeMap<YearMonth, String> replayable = new TreeMap<>();
        for (ReplayMonth month : months) {
            LocalDate start = month.monthStart();
            if (!start.isBefore(replayStart) && start.isBefore(upper)) {
                replayable.put(YearMonth.from(start), month.regimeLabel() == null ? "-" : month.regimeLabel().name());
            }
        }
        List<String> lines = new ArrayList<>(replayable.size() + 1);
        lines.add(engineVersion);
        replayable.forEach((month, label) -> lines.add(month + ":" + label));
        return sha256Hex(String.join("\n", lines));
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
