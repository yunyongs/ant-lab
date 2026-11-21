package antlab.history;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.engine.client.EngineProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The replay start default is written in three places that must agree: Java, application.yml and the psql default of db/20 and db/11 check. */
class ReplayStartDefaultParityTest {

    private static String first(Path file, String regex) throws IOException {
        Matcher m = Pattern.compile(regex).matcher(Files.readString(file));
        assertThat(m.find()).as("%s has %s", file, regex).isTrue();
        return m.group(1);
    }

    @Test
    void the_defaults_match() throws IOException {
        String java = HistoryProperties.DEFAULT_REPLAY_START.toString();
        assertThat(first(Path.of("src/main/resources/application.yml"), "replay-start: \\$\\{ANTLAB_HISTORY_REPLAY_START:([0-9-]+)\\}"))
                .isEqualTo(java);
        assertThat(first(Path.of("src/main/java/antlab/history/HistoryProperties.java"), "@DefaultValue\\(\"([0-9-]+)\"\\)"))
                .isEqualTo(java);
        assertThat(first(Path.of("db/20_season_start.sql"), "\\\\set replay_start ([0-9-]+)")).isEqualTo(java);
        assertThat(first(Path.of("db/20_season_start_check.sql"), "\\\\set replay_start ([0-9-]+)")).isEqualTo(java);
        assertThat(first(Path.of("db/11_load_events_check.sql"), "\\\\set replay_start ([0-9-]+)")).isEqualTo(java);
    }

    @Test
    void the_engine_constant_equals_the_engine_source() throws IOException {
        String text = first(Path.of("engine/src/antlab_engine/bootstrap/simple.py"), "REPLAY_START = date\\((\\d+, \\d+, \\d+)\\)");
        String[] y = text.split(", ");
        assertThat(LocalDate.of(Integer.parseInt(y[0]), Integer.parseInt(y[1]), Integer.parseInt(y[2])))
                .isEqualTo(EngineProperties.ENGINE_REPLAY_START);
    }

    @Test
    void db_20_no_longer_holds_the_literal_in_its_checks() throws IOException {
        for (String file : new String[] {"db/20_season_start.sql", "db/20_season_start_check.sql"}) {
            String code = Files.readAllLines(Path.of(file)).stream().filter(l -> !l.stripLeading().startsWith("--"))
                    .reduce("", (a, b) -> a + b + "\n");
            assertThat(code).as(file).doesNotContain("DATE '2007-01-01'");
        }
    }
}
