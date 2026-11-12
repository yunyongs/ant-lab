package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 응답 문서(openapi.yaml 의 getSeasonReport 설명)의 "제외" 목록이 SQL 정의 머리의 X1–X12 와 글자 그대로 같다(공백만 접는다).
 * 26 머리를 고치면 계약(yaml)과 컨트롤러 설명도 같은 변경에서 고친다(P0-21 "골든 파일" 규칙).
 */
class ReportExclusionListTest {

    static final Path DEFINITION = Path.of("db/26_season_report_def.sql");
    static final Path CONTRACT = Path.of("docs/api/openapi.yaml");

    private static String fold(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /** 정의 머리의 "--   X7  이름 … 이유" 항목. 이어지는 들여쓴 주석 줄은 앞 항목에 붙인다. */
    static List<String> definitionEntries() throws IOException {
        List<String> entries = new ArrayList<>();
        StringBuilder current = null;
        Pattern start = Pattern.compile("^--\\s+(X\\d+)\\s+(.*)$");
        for (String line : Files.readAllLines(DEFINITION)) {
            Matcher m = start.matcher(line);
            if (m.matches()) {
                if (current != null) {
                    entries.add(fold(current.toString()));
                }
                current = new StringBuilder(m.group(1) + " " + m.group(2));
            } else if (current != null && line.matches("^--\\s{10,}\\S.*")) {
                current.append(' ').append(line.substring(2).strip());
            } else if (current != null) {
                entries.add(fold(current.toString()));
                current = null;
            }
        }
        if (current != null) {
            entries.add(fold(current.toString()));
        }
        return entries;
    }

    /** yaml 의 getSeasonReport 운영 설명(literal 블록)에서 "- X…" 줄. */
    static List<String> documentedEntries() throws IOException {
        List<String> lines = Files.readAllLines(CONTRACT);
        int op = lines.indexOf("      operationId: getSeasonReport");
        assertThat(op).as("getSeasonReport 운영").isPositive();
        List<String> out = new ArrayList<>();
        boolean inDescription = false;
        for (int i = op + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("      description:")) {
                inDescription = true;
                continue;
            }
            if (inDescription) {
                if (!line.isEmpty() && !line.startsWith("        ")) {
                    break;
                }
                if (line.strip().startsWith("- X")) {
                    out.add(fold(line.strip().substring(2)));
                }
            }
        }
        return out;
    }

    @Test
    void 제외_목록은_정의_머리의_열두_항목과_글자_그대로_같다() throws IOException {
        List<String> definition = definitionEntries();
        assertThat(definition).hasSize(12);
        assertThat(definition.get(0)).startsWith("X1 ");
        assertThat(definition.get(11)).startsWith("X12 ");
        assertThat(documentedEntries()).containsExactlyElementsOf(definition);
    }

    @Test
    void 컨트롤러_설명도_같은_열두_항목을_싣는다() throws Exception {
        String operation = SeasonReportDescriptions.controllerDescription();
        for (String entry : definitionEntries()) {
            assertThat(fold(operation)).contains(entry);
        }
    }
}
