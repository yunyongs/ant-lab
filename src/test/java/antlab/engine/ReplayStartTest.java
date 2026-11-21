package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.history.ReplayMonth;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** S1 with a replay start later than the first month of the data: months before it are warm-up and never drawn. */
class ReplayStartTest {

    private static final LocalDate BASE = SyntheticMonths.BASE_DATE;
    private static final LocalDate LATER = LocalDate.of(2010, 1, 1);

    @Test
    void warm_up_months_are_never_drawn() {
        List<ReplayMonth> months = SyntheticMonths.sample();
        Set<LocalDate> seenBefore2010 = new HashSet<>();
        for (int length : List.of(12, 24)) {
            for (long seed = 0; seed < 300; seed++) {
                PathRequest req = new PathRequest(BASE, length, seed);
                List<LocalDate> path = SimpleBootstrap.generateMonths(months, req, LATER);
                assertThat(path).as("length=%d seed=%d", length, seed).hasSize(length).allSatisfy(
                        m -> assertThat(m).isAfterOrEqualTo(LATER));
                assertThat(PathRules.violations(path, BASE, length, 6, LATER)).isEmpty();
                // the default start does draw earlier months from the same data, so the setting is what excludes them
                SimpleBootstrap.generateMonths(months, req).stream().filter(m -> m.isBefore(LATER)).forEach(seenBefore2010::add);
            }
        }
        assertThat(seenBefore2010).as("the default start reaches 2007-2009").isNotEmpty();
    }

    @Test
    void the_id_path_follows_the_same_start() {
        List<ReplayMonth> months = SyntheticMonths.sample();
        PathRequest req = new PathRequest(BASE, 24, 7L);
        List<LocalDate> viaMonths = SimpleBootstrap.generateMonths(months, req, LATER);
        GeneratedPath path = SimpleBootstrap.generate(months, req, LATER);
        List<LocalDate> viaIds = new ArrayList<>();
        for (Long id : path.monthBlockIds()) {
            viaIds.add(months.stream().filter(m -> m.monthBlockId().equals(id)).findFirst().orElseThrow().monthStart());
        }
        assertThat(viaIds).isEqualTo(viaMonths);
    }

    @Test
    void a_path_that_starts_before_the_replay_start_breaks_s1() {
        List<LocalDate> early = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            early.add(LocalDate.of(2008, 1, 1).plusMonths(i));
        }
        assertThat(PathRules.violations(early, BASE, 12, 6, LocalDate.of(2007, 1, 1))).doesNotContain("S1_RANGE");
        assertThat(PathRules.violations(early, BASE, 12, 6, LATER)).contains("S1_RANGE");
    }

    @Test
    void the_history_digest_leaves_out_months_before_the_start() {
        List<LocalDate> months = List.of(LocalDate.of(2007, 1, 1), LocalDate.of(2010, 1, 1), LocalDate.of(2010, 2, 1));
        String later = HistoryDigest.of("m2-sb6-v1", months, BASE, LATER);
        assertThat(later).isEqualTo(HistoryDigest.of("m2-sb6-v1", months.subList(1, 3), BASE));
        assertThat(later).isNotEqualTo(HistoryDigest.of("m2-sb6-v1", months, BASE));
    }
}
