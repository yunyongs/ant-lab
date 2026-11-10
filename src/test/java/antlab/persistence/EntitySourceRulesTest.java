package antlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 엔티티 소스의 구조 규칙(소스 grep, 스프링 문맥 없음): 컬렉션 연관·setter·{@code @Data}·{@code @Builder} 없음,
 * 반올림 없음(엔티티는 계산하지 않는다 — 반올림은 MoneyMath 한 곳), {@code @ManyToOne} 은 모두 LAZY.
 * 검사기 자체를 먼저 음성 사례로 확인한다.
 */
class EntitySourceRulesTest {

    private static final List<String> PACKAGES = List.of("season", "account", "member", "scoring", "common/persistence");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "@OneToMany|@OneToOne|@ManyToMany|@Setter|@Data\\b|@Builder|@AllArgsConstructor|setScale|RoundingMode");
    private static final Pattern MANY_TO_ONE = Pattern.compile("@ManyToOne(\\([^)]*\\))?");

    static List<String> violations(String source) {
        List<String> found = new ArrayList<>();
        Matcher f = FORBIDDEN.matcher(source);
        while (f.find()) {
            found.add(f.group());
        }
        Matcher m = MANY_TO_ONE.matcher(source);
        while (m.find()) {
            if (!m.group().contains("FetchType.LAZY")) {
                found.add(m.group() + " 은 LAZY 가 아니다");
            }
        }
        return found;
    }

    @Test
    void 검사기는_위반_사례를_잡는다() {
        assertThat(violations("@OneToMany List<X> xs;")).isNotEmpty();
        assertThat(violations("@Setter class A {}")).isNotEmpty();
        assertThat(violations("v.setScale(0, RoundingMode.HALF_UP)")).hasSize(2);
        assertThat(violations("@ManyToOne private Season s;")).isNotEmpty();
        assertThat(violations("@ManyToOne(fetch = FetchType.EAGER) private Season s;")).isNotEmpty();
        assertThat(violations("@ManyToOne(fetch = FetchType.LAZY, optional = false) private Season s;")).isEmpty();
    }

    @Test
    void 시즌_계좌_회원_대사_패키지에_금지된_구조가_없다() throws IOException {
        List<String> problems = new ArrayList<>();
        int files = 0;
        for (String pkg : PACKAGES) {
            try (Stream<Path> walk = Files.walk(Path.of("src/main/java/antlab", pkg))) {
                for (Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                    files++;
                    violations(Files.readString(p)).forEach(v -> problems.add(p.getFileName() + ": " + v));
                }
            }
        }
        assertThat(files).as("검사한 파일 수").isGreaterThan(40);
        assertThat(problems).isEmpty();
    }
}
