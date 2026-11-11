package antlab.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 검증 SQL의 마지막 판정 행을 읽는다.
 *
 * <p>psql을 {@code -q -A -P footer=off} 로 실행한 출력의 끝 두 줄(비어 있지 않은 줄)이
 * 등록한 판정 열 이름과 정확히 {@code PASS} 일 때만 통과다. 앞쪽의 PASS, 다른 검사 이름,
 * 판정 뒤의 추가 결과는 통과시키지 않는다. 실패 사유에는 원문을 싣지 않고 분류만 남긴다.
 */
public final class CheckVerdictParser {

    /** 판정 결과. {@code reason} 은 원문이 아닌 분류 문구다. */
    public record Verdict(boolean passed, String reason) {
    }

    private CheckVerdictParser() {
    }

    /**
     * 판정 열 뒤에 건수 열이 붙는 마지막 판정(드릴: {@code p0_17_drill|total|pass|fail} / {@code PASS|n|n|0})을 읽는다.
     * 끝 두 줄의 첫 칸이 등록한 열 이름과 {@code PASS} 일 때만 통과이고 {@code fail} 건수는 0이어야 한다.
     * 한 열짜리 판정은 {@link #parse} 를 쓴다(이 메서드는 건수 열이 있는 드릴 전용이라 엄격도를 낮추지 않는다).
     */
    public static Verdict parseWithCounts(String stdout, String verdictColumn) {
        List<String> lines = new ArrayList<>();
        for (String line : stdout.split("\\R")) {
            if (!line.strip().isEmpty()) {
                lines.add(line.strip());
            }
        }
        if (lines.size() < 2) {
            return new Verdict(false, "판정 행 없음");
        }
        String[] header = lines.get(lines.size() - 2).split("\\|", -1);
        String[] value = lines.get(lines.size() - 1).split("\\|", -1);
        if (header.length != 4 || value.length != 4 || !header[0].equals(verdictColumn)
                || !header[1].equals("total") || !header[2].equals("pass") || !header[3].equals("fail")) {
            return new Verdict(false, "마지막 판정 열 이름이 등록값과 다름 (판정 누락, 다른 검사 이름 또는 판정 뒤 추가 결과)");
        }
        for (int i = 1; i <= 3; i++) {
            if (!value[i].matches("\\d+")) {
                return new Verdict(false, "판정 건수가 맞지 않음");
            }
        }
        long total = Long.parseLong(value[1]);
        if (total <= 0 || total != Long.parseLong(value[2]) || Long.parseLong(value[3]) != 0) {
            return new Verdict(false, "판정 건수가 맞지 않음");
        }
        if (!value[0].equals("PASS")) {
            return new Verdict(false, value[0].equals("FAIL") ? "마지막 판정이 FAIL" : "마지막 판정 값이 PASS가 아님");
        }
        return new Verdict(true, "PASS");
    }

    public static Verdict parse(String stdout, String verdictColumn) {
        List<String> lines = new ArrayList<>();
        for (String line : stdout.split("\\R")) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                lines.add(stripped);
            }
        }
        if (lines.size() < 2) {
            return new Verdict(false, "판정 행 없음");
        }
        String header = lines.get(lines.size() - 2);
        String value = lines.get(lines.size() - 1);
        if (!header.equals(verdictColumn)) {
            return new Verdict(false, "마지막 판정 열 이름이 등록값과 다름 (판정 누락, 다른 검사 이름 또는 판정 뒤 추가 결과)");
        }
        if (!value.equals("PASS")) {
            return new Verdict(false, value.equals("FAIL") ? "마지막 판정이 FAIL" : "마지막 판정 값이 PASS가 아님");
        }
        return new Verdict(true, "PASS");
    }
}
