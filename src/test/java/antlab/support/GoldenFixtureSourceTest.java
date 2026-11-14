package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoldenFixtureSourceTest {

    private static final Path REPO = Path.of("").toAbsolutePath();

    private static void writeAll(Path dir, String content) throws Exception {
        Files.createDirectories(dir);
        for (String name : GoldenFixtureSource.UNTRACKED_CSV) {
            Files.writeString(dir.resolve(name), content);
        }
    }

    @Test
    void 표본_디렉터리에_파일이_없으면_누락_목록과_안내를_낸다(@TempDir Path empty) {
        var availability = new GoldenFixtureSource(REPO, empty).inspect();

        assertThat(availability.isComplete()).isFalse();
        assertThat(availability.missing()).containsExactlyElementsOf(GoldenFixtureSource.UNTRACKED_CSV);
        assertThat(availability.skipMessage())
                .startsWith("실제 표본 골든을 건너뜁니다. 누락 파일: asset_month_return.csv, asset_position.csv, fx_month.csv, hand_check.csv.")
                .contains("‘표본 만들기’").contains(GoldenFixtureSource.ENV_SAMPLE_DIR);
    }

    @Test
    void 일부만_있으면_없는_파일만_누락으로_본다(@TempDir Path dir) throws Exception {
        writeAll(dir, "x\n");
        Files.delete(dir.resolve("hand_check.csv"));
        assertThat(new GoldenFixtureSource(REPO, dir).inspect().missing()).containsExactly("hand_check.csv");
    }

    @Test
    void 누락이_있으면_입력을_모으지_않는다(@TempDir Path empty) {
        assertThatThrownBy(() -> new GoldenFixtureSource(REPO, empty).collect())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("건너뜁니다");
    }

    @Test
    void 입력_묶음은_정해진_파일만_담고_드릴과_비밀값_폴더는_넣지_않는다(@TempDir Path dir) throws Exception {
        writeAll(dir, "h\n1\n");
        var bundle = new GoldenFixtureSource(REPO, dir).collect();
        var paths = bundle.files().stream().map(GoldenFixtureSource.StagedFile::relativePath).toList();

        assertThat(paths).contains("db/10_load_sample.sql", "db/23_turn_advance_def.sql", "db/sample/hand_check.csv",
                "db/sample/asset.csv", "events/types.csv", "feed/templates.csv", "feed/variables.csv", "feed/denylist.csv");
        assertThat(paths).noneMatch(p -> p.endsWith("_drill.sql"))
                .noneMatch(p -> p.contains(".env")).noneMatch(p -> p.contains("data/raw"))
                .allMatch(p -> p.startsWith("db/") || p.startsWith("events/") || p.startsWith("feed/"));
        // 호스트의 추적하지 않는 파일은 네 개만 읽는다
        assertThat(paths.stream().filter(p -> p.startsWith("db/sample/")).count()).isEqualTo(3 + 4);
    }

    @Test
    void 파일_내용이_바뀌면_묶음_해시가_달라지고_같으면_같다(@TempDir Path dir) throws Exception {
        writeAll(dir, "h\n1\n");
        var first = new GoldenFixtureSource(REPO, dir).collect();
        var again = new GoldenFixtureSource(REPO, dir).collect();
        Files.writeString(dir.resolve("fx_month.csv"), "h\n2\n");
        var changed = new GoldenFixtureSource(REPO, dir).collect();

        assertThat(again.bundleSha256()).isEqualTo(first.bundleSha256());
        assertThat(changed.bundleSha256()).isNotEqualTo(first.bundleSha256());
    }

    @Test
    void 묶음_표기에는_내용이_나오지_않는다(@TempDir Path dir) throws Exception {
        writeAll(dir, "price_secret\n9999.1234\n");
        var bundle = new GoldenFixtureSource(REPO, dir).collect();
        assertThat(bundle.toString()).doesNotContain("9999").doesNotContain("price_secret");
        bundle.files().forEach(f -> assertThat(f.toString()).doesNotContain("9999"));
    }

    @Test
    void 지정했는데_파일이_없으면_실패하고_미지정이면_건너뛴다(@TempDir Path empty) {
        var explicitMissing = new GoldenFixtureSource(REPO, empty).inspect();
        assertThat(explicitMissing.explicit()).isTrue();
        assertThatThrownBy(explicitMissing::requireOrSkip).isInstanceOf(AssertionError.class)
                .hasMessageContaining(GoldenFixtureSource.ENV_SAMPLE_DIR).hasMessageContaining("hand_check.csv")
                .hasMessageNotContaining(empty.toString());

        var implicitMissing = new GoldenFixtureSource.FixtureAvailability(GoldenFixtureSource.UNTRACKED_CSV, false);
        assertThatThrownBy(implicitMissing::requireOrSkip).isInstanceOf(org.opentest4j.TestAbortedException.class);
    }

    @Test
    void 지정했고_파일이_모두_있으면_통과한다(@TempDir Path dir) throws Exception {
        writeAll(dir, "x\n");
        var availability = new GoldenFixtureSource(REPO, dir).inspect();
        assertThat(availability.explicit()).isTrue();
        availability.requireOrSkip();
    }
}
