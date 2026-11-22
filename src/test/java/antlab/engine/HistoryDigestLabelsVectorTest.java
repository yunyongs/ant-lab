package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.RegimePool;
import antlab.history.ReplayMonth;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The same file the engine's digest test reads: both sides must produce the digest on every row group. */
class HistoryDigestLabelsVectorTest {

    private record Case(String version, LocalDate baseDate, LocalDate replayStart, String digest, List<ReplayMonth> months) {
    }

    private static Map<String, Case> cases() throws IOException {
        List<String> lines;
        try (InputStream in = HistoryDigestLabelsVectorTest.class.getResourceAsStream("/engine/parity/history-digest-labels-v1.csv")) {
            assertThat(in).isNotNull();
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        assertThat(lines.get(0)).isEqualTo("case,version,base_date,replay_start,month,label,digest");
        Map<String, Case> cases = new LinkedHashMap<>();
        long id = 1;
        for (String line : lines.subList(1, lines.size())) {
            String[] cols = line.split(",", -1);
            Case c = cases.computeIfAbsent(cols[0], k -> new Case(cols[1], LocalDate.parse(cols[2]),
                    LocalDate.parse(cols[3]), cols[6], new ArrayList<>()));
            assertThat(cols[6]).as(cols[0]).isEqualTo(c.digest());
            RegimePool label = cols[5].isEmpty() ? null : RegimePool.valueOf(cols[5]);
            c.months().add(new ReplayMonth(id++, LocalDate.parse(cols[4] + "-01"), label));
        }
        return cases;
    }

    @Test
    void 라벨_이력_요약값은_엔진_벡터와_같다() throws IOException {
        Map<String, Case> cases = cases();
        assertThat(cases).isNotEmpty();
        assertThat(cases.values().stream().anyMatch(c -> c.version().equals("m2-sbc6-v1"))).isTrue();
        for (Map.Entry<String, Case> entry : cases.entrySet()) {
            Case c = entry.getValue();
            String actual = c.version().equals(antlab.engine.client.EnginePathRequest.CONDITIONAL_VERSION)
                    ? HistoryDigest.ofLabelled(c.version(), c.months(), c.baseDate(), c.replayStart())
                    : HistoryDigest.of(c.version(), c.months().stream().map(ReplayMonth::monthStart).toList(), c.baseDate(),
                            c.replayStart());
            assertThat(actual).as(entry.getKey()).isEqualTo(c.digest());
        }
    }

    @Test
    void 재생_가능한_달의_라벨은_요약값을_바꾸고_범위_밖_라벨은_바꾸지_않는다() {
        LocalDate base = LocalDate.of(2026, 1, 2);
        LocalDate start = LocalDate.of(2007, 1, 1);
        List<ReplayMonth> months = List.of(
                new ReplayMonth(1L, LocalDate.of(2006, 12, 1), RegimePool.P1),
                new ReplayMonth(2L, LocalDate.of(2007, 1, 1), RegimePool.P2),
                new ReplayMonth(3L, LocalDate.of(2007, 2, 1), null),
                new ReplayMonth(4L, LocalDate.of(2026, 1, 1), RegimePool.P3));
        String v = "m2-sbc6-v1";
        String reference = HistoryDigest.ofLabelled(v, months, base, start);

        List<ReplayMonth> outside = List.of(
                new ReplayMonth(1L, LocalDate.of(2006, 12, 1), RegimePool.P4),
                months.get(1), months.get(2),
                new ReplayMonth(4L, LocalDate.of(2026, 1, 1), null));
        List<ReplayMonth> inside = List.of(months.get(0),
                new ReplayMonth(2L, LocalDate.of(2007, 1, 1), RegimePool.P3), months.get(2), months.get(3));

        assertThat(HistoryDigest.ofLabelled(v, outside, base, start)).isEqualTo(reference);
        assertThat(HistoryDigest.ofLabelled(v, inside, base, start)).isNotEqualTo(reference);
        assertThat(reference).isNotEqualTo(HistoryDigest.of(v, months.stream().map(ReplayMonth::monthStart).toList(), base, start));
    }
}
