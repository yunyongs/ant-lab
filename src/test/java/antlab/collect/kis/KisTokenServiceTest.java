package antlab.collect.kis;

import static antlab.collect.kis.KisHarness.FAKE_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.kis.KisHarness.AdvancingClock;
import antlab.collect.kis.KisHarness.RecordingSleeper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KisTokenServiceTest {

    @TempDir
    Path dir;

    private AdvancingClock clock;
    private RecordingSleeper sleeper;
    private final SecretMasker masker = new SecretMasker();
    private final AtomicInteger issued = new AtomicInteger();

    @BeforeEach
    void setUp() {
        clock = new AdvancingClock(KisHarness.START);
        sleeper = new RecordingSleeper(clock);
    }

    private KisTokenService service(boolean withFile) {
        return new KisTokenService(clock, sleeper, withFile ? new FileKisTokenStore(dir) : null, masker);
    }

    private StoredToken issue(long ttlSeconds) {
        return new StoredToken(FAKE_TOKEN + "-" + issued.incrementAndGet(), clock.instant().getEpochSecond() + ttlSeconds);
    }

    @Test
    void 메모리의_토큰은_다시_쓴다() {
        KisTokenService service = service(false);

        String first = service.accessToken(() -> issue(86_400));
        String second = service.accessToken(() -> issue(86_400));

        assertThat(second).isEqualTo(first);
        assertThat(issued.get()).isEqualTo(1);
    }

    @Test
    void 같은_파일을_보는_다른_서비스는_발급하지_않고_파일의_토큰을_쓴다() {
        String first = service(true).accessToken(() -> issue(86_400));

        String second = service(true).accessToken(() -> issue(86_400));

        assertThat(second).isEqualTo(first);
        assertThat(issued.get()).isEqualTo(1);
    }

    @Test
    void 남은_시간이_한_시간_이하면_새로_발급하고_초과면_다시_쓴다() {
        KisTokenService service = service(true);
        service.accessToken(() -> issue(KisTokenService.REUSE_SECONDS));
        service.accessToken(() -> issue(KisTokenService.REUSE_SECONDS));
        assertThat(issued.get()).as("exactly one hour left is not enough").isEqualTo(2);

        KisTokenService fresh = service(true);
        fresh.accessToken(() -> issue(KisTokenService.REUSE_SECONDS + 1));
        fresh.accessToken(() -> issue(KisTokenService.REUSE_SECONDS + 1));
        assertThat(issued.get()).as("one second more than an hour is reused").isEqualTo(3);
    }

    @Test
    void 시간이_흘러_한_시간_이하가_되면_다음_호출이_새로_발급한다() {
        KisTokenService service = service(false);
        service.accessToken(() -> issue(86_400));

        clock.advance(Duration.ofSeconds(86_400 - KisTokenService.REUSE_SECONDS));
        service.accessToken(() -> issue(86_400));

        assertThat(issued.get()).isEqualTo(2);
    }

    @Test
    void 발급_사이에는_65초를_둔다() {
        KisTokenService service = service(false);
        service.accessToken(() -> issue(86_400));
        service.reject(FAKE_TOKEN + "-1");

        service.accessToken(() -> issue(86_400));

        assertThat(sleeper.sleeps).containsExactly(KisTokenService.ISSUANCE_SPACING);
        assertThat(issued.get()).isEqualTo(2);
    }

    @Test
    void 파일은_소유자만_읽고_쓰는_권한이고_tools_fetch_형식이다() throws Exception {
        service(true).accessToken(() -> issue(86_400));

        Path file = dir.resolve("token_real.json");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(Files.readString(file)).contains("\"access_token\"", "\"expires_at\"")
                .contains(Long.toString(KisHarness.START.getEpochSecond() + 86_400));
        try (var listing = Files.list(dir)) {
            assertThat(listing.map(p -> p.getFileName().toString())).containsExactly("token_real.json");
        }
    }

    @Test
    void 파이썬_도구가_쓴_파일의_소수_만료_시각도_읽는다() throws Exception {
        long expires = KisHarness.START.getEpochSecond() + 86_400;
        Files.writeString(dir.resolve("token_real.json"),
                "{\"access_token\": \"" + FAKE_TOKEN + "\", \"expires_at\": " + expires + ".123456}");

        String token = service(true).accessToken(() -> issue(86_400));

        assertThat(token).isEqualTo(FAKE_TOKEN);
        assertThat(issued.get()).isZero();
    }

    @Test
    void 깨진_파일은_없는_것으로_보고_발급한다() throws Exception {
        Files.writeString(dir.resolve("token_real.json"), "{not json");

        service(true).accessToken(() -> issue(86_400));

        assertThat(issued.get()).isEqualTo(1);
        assertThat(new FileKisTokenStore(dir).load()).isPresent();
    }

    @Test
    void 거절된_토큰은_파일에서도_지우고_다른_토큰은_건드리지_않는다() {
        KisTokenService service = service(true);
        String token = service.accessToken(() -> issue(86_400));
        FileKisTokenStore store = new FileKisTokenStore(dir);

        store.discard("some-other-token");
        assertThat(store.load()).isPresent();
        service.reject(token);

        assertThat(store.load()).isEmpty();
    }

    @Test
    void 발급이_두_번_실패하면_마지막_오류를_그대로_던진다() {
        KisTokenService service = service(false);

        assertThatThrownBy(() -> service.accessToken(() -> {
            issued.incrementAndGet();
            throw new KisException("KIS_REJECTED", "no token");
        })).isInstanceOf(KisException.class).hasMessage("no token");
        assertThat(issued.get()).isEqualTo(2);
    }

    @Test
    void 저장된_토큰_객체의_문자열에는_토큰이_없다() {
        assertThat(new StoredToken(FAKE_TOKEN, 1L).toString()).doesNotContain(FAKE_TOKEN).contains("****");
    }
}
