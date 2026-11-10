package antlab.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 골든 시험이 컨테이너에 공급하는 입력 파일을 모으고 해시로 고정한다.
 *
 * <p>SQL·events·추적 CSV 는 현재 작업본(저장소 루트)에서 가져오고, 추적하지 않는 시세·환율 파생 CSV 네 개만
 * 표본 디렉터리에서 가져온다. 표본 디렉터리는 환경 변수 {@value #ENV_SAMPLE_DIR} 이 있으면 그 값이고 없으면
 * 작업본의 {@code db/sample} 이다. 개인 경로를 기본값으로 두지 않는다. 호스트 파일은 읽기만 한다.
 */
public final class GoldenFixtureSource {

    public static final String ENV_SAMPLE_DIR = "ANTLAB_GOLDEN_SAMPLE_DIR";

    /** 추적하지 않는 파일 네 개 (db/sample/README.md 의 "표본 만들기" 산출물) */
    public static final List<String> UNTRACKED_CSV = List.of(
            "asset_month_return.csv", "asset_position.csv", "fx_month.csv", "hand_check.csv");

    private static final List<String> TRACKED_SAMPLE_CSV = List.of("asset_group.csv", "asset.csv", "month_block.csv");
    private static final List<String> EVENT_CSV = List.of("types.csv", "tags.csv", "reviewed.csv");
    /** 24 피드 정의가 상대 경로(feed/)로 \copy 하는 추적 파일 (턴 전환 23 이 읽는다) */
    private static final List<String> FEED_CSV = List.of("templates.csv", "variables.csv");

    /**
     * 준비 상태. 누락 파일 이름만 담는다.
     *
     * @param explicit 환경 변수 {@value #ENV_SAMPLE_DIR} 로 표본 위치를 지정했는가
     */
    public record FixtureAvailability(List<String> missing, boolean explicit) {
        public boolean isComplete() {
            return missing.isEmpty();
        }

        public String failMessage() {
            return ENV_SAMPLE_DIR + " 로 지정한 표본 위치에 파일이 없습니다. 누락 파일: " + String.join(", ", missing)
                    + ". 경로를 확인하세요.";
        }

        /**
         * 지정했는데 파일이 없으면 실패(운영자 오류), 지정하지 않았고 없으면 건너뜀(정상적인 표본 없음).
         * 실제 표본 골든 시험은 시작할 때 이 메서드 한 곳으로 판단한다.
         */
        public void requireOrSkip() {
            if (explicit && !missing.isEmpty()) {
                throw new AssertionError(failMessage());
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(isComplete(), this::skipMessage);
        }

        public String skipMessage() {
            return "실제 표본 골든을 건너뜁니다. 누락 파일: " + String.join(", ", missing)
                    + ". db/sample/README.md의 ‘표본 만들기’를 확인하거나 " + ENV_SAMPLE_DIR + "를 지정하세요.";
        }
    }

    /** 컨테이너에 놓을 입력 하나. 내용은 읽은 시점의 바이트로 고정한다. */
    public record StagedFile(String relativePath, byte[] content, String sha256) {
        @Override
        public String toString() {
            return "StagedFile[" + relativePath + ", sha256=" + sha256 + "]";
        }
    }

    /** 입력 묶음. 경로와 해시만 보고한다. */
    public record InputBundle(List<StagedFile> files, String bundleSha256) {
        @Override
        public String toString() {
            return "InputBundle[files=" + files.size() + ", sha256=" + bundleSha256 + "]";
        }
    }

    private final Path repositoryRoot;
    private final Path sampleDir;
    private final boolean explicit;

    public GoldenFixtureSource(Path repositoryRoot, Path externalSampleDir) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath();
        this.explicit = externalSampleDir != null;
        this.sampleDir = externalSampleDir != null ? externalSampleDir : this.repositoryRoot.resolve("db/sample");
    }

    public static GoldenFixtureSource fromEnvironment() {
        String env = System.getenv(ENV_SAMPLE_DIR);
        Path external = env == null || env.isBlank() ? null : Path.of(env);
        return new GoldenFixtureSource(Path.of(""), external);
    }

    public FixtureAvailability inspect() {
        List<String> missing = new ArrayList<>();
        for (String name : UNTRACKED_CSV) {
            if (!Files.isRegularFile(sampleDir.resolve(name))) {
                missing.add(name);
            }
        }
        return new FixtureAvailability(missing, explicit);
    }

    /** 입력 파일을 한 번 읽어 바이트와 해시를 고정한다. 누락이 있으면 예외다(건너뜀 판단은 호출 전에 한다). */
    public InputBundle collect() throws IOException {
        FixtureAvailability availability = inspect();
        if (!availability.isComplete()) {
            throw new IllegalStateException(availability.skipMessage());
        }
        Map<String, Path> sources = new TreeMap<>();
        try (Stream<Path> sql = Files.list(repositoryRoot.resolve("db"))) {
            sql.filter(p -> p.getFileName().toString().matches("\\d{2}_.*\\.sql"))
                    // 드릴은 기본 회귀 밖이다 (파괴·롤백 사례는 명시적으로 고른 별도 DB 에서만)
                    .filter(p -> !p.getFileName().toString().endsWith("_drill.sql"))
                    .forEach(p -> sources.put("db/" + p.getFileName(), p));
        }
        for (String name : TRACKED_SAMPLE_CSV) {
            sources.put("db/sample/" + name, repositoryRoot.resolve("db/sample/" + name));
        }
        for (String name : UNTRACKED_CSV) {
            sources.put("db/sample/" + name, sampleDir.resolve(name));
        }
        for (String name : EVENT_CSV) {
            sources.put("events/" + name, repositoryRoot.resolve("events/" + name));
        }
        for (String name : FEED_CSV) {
            sources.put("feed/" + name, repositoryRoot.resolve("feed/" + name));
        }
        List<StagedFile> files = new ArrayList<>();
        StringBuilder manifest = new StringBuilder();
        for (Map.Entry<String, Path> entry : sources.entrySet()) {
            byte[] content = Files.readAllBytes(entry.getValue());
            String hash = sha256(content);
            files.add(new StagedFile(entry.getKey(), content, hash));
            manifest.append(entry.getKey()).append(':').append(hash).append('\n');
        }
        return new InputBundle(List.copyOf(files), sha256(manifest.toString().getBytes(StandardCharsets.UTF_8)));
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
