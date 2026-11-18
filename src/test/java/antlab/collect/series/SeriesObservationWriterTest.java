package antlab.collect.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.CollectTestData;
import antlab.collect.job.RunLostException;
import antlab.collect.series.SeriesObservationWriter.Incoming;
import antlab.collect.series.SeriesObservationWriter.Mode;
import antlab.collect.series.SeriesObservationWriter.Result;
import antlab.support.JpaMappingTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The append-only writer on its own: both modes, the mismatch cases, atomicity per series, and a lost run. */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeriesObservationWriterTest {

    private static final LocalDate FROM = LocalDate.of(1980, 1, 1);
    private static final Instant T1 = Instant.parse("2026-11-18T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-19T10:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private SeriesObservationWriter writer;
    private long series;
    private long run;

    @BeforeEach
    void setUp() {
        new CollectTestData(jdbc).cleanUp();
        writer = new SeriesObservationWriter(jdbc, transactions);
        series = new MacroFixtures(jdbc).series("TEST_SERIES", "FRED", "TESTSERIES", true);
        run = openRun(T1);
    }

    @AfterEach
    void clean() {
        new CollectTestData(jdbc).cleanUp();
    }

    private long openRun(Instant startedAt) {
        return jdbc.queryForObject("INSERT INTO job_run (job_name, run_trigger, status, started_at) "
                + "VALUES ('FRED_SERIES', 'MANUAL', 'RUNNING', ?::timestamptz) RETURNING id", Long.class, startedAt.toString());
    }

    private void close(long runId) {
        jdbc.update("UPDATE job_run SET status = 'SUCCEEDED', finished_at = started_at + interval '1 minute', request_count = 0, inserted_count = 0, revised_count = 0, "
                + "unchanged_count = 0, failed_target_count = 0 WHERE id = ?", runId);
    }

    private static Incoming in(String ref, String value, String realtimeStart) {
        return new Incoming(LocalDate.parse(ref), new BigDecimal(value), realtimeStart == null ? null : LocalDate.parse(realtimeStart), null);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM series_observation", Integer.class);
    }

    @Test
    void 빈_입력은_아무것도_하지_않고_실행_상태도_보지_않는다() {
        assertThat(writer.write(-1, T1, series, Mode.LATEST, FROM, List.of())).isEqualTo(new Result(0, 0, 0));
    }

    @Test
    void 최신_값_방식은_새_날짜는_1번_같은_값은_변동_없음_다른_값은_다음_번호다() {
        assertThat(writer.write(run, T1, series, Mode.LATEST, FROM, List.of(in("2025-11-17", "3.6", null), in("2025-11-18", "3.5", null))))
                .isEqualTo(new Result(2, 0, 0));
        close(run);
        long second = openRun(T2);

        Result result = writer.write(second, T2, series, Mode.LATEST, FROM,
                List.of(in("2025-11-17", "3.60", null), in("2025-11-18", "3.4", null), in("2025-11-19", "3.3", null)));

        assertThat(result).isEqualTo(new Result(2, 1, 1));
        assertThat(jdbc.queryForList("SELECT ref_date::text || '#' || vintage_no FROM series_observation WHERE ref_date >= DATE '2025-11-18' ORDER BY ref_date, vintage_no",
                String.class)).containsExactly("2025-11-18#1", "2025-11-18#2", "2025-11-19#1");
        assertThat(writer.latestRefDate(series)).isEqualTo(LocalDate.of(2025, 11, 19));
    }

    @Test
    void 최신_값_방식의_개정은_사용_가능일을_저장한_날보다_앞에_두지_않고_첫_빈티지와_늦은_날짜는_그대로다() {
        LocalDate lag = LocalDate.parse("2025-11-07");
        writer.write(run, T1, series, Mode.LATEST, FROM, List.of(new Incoming(LocalDate.parse("2025-10-01"), new BigDecimal("1"), null, lag),
                new Incoming(LocalDate.parse("2025-09-01"), new BigDecimal("5"), null, LocalDate.parse("2030-01-01")),
                new Incoming(LocalDate.parse("2025-08-01"), new BigDecimal("6"), null, null)));
        close(run);
        long second = openRun(T2);

        writer.write(second, T2, series, Mode.LATEST, FROM, List.of(new Incoming(LocalDate.parse("2025-10-01"), new BigDecimal("2"), null, lag),
                new Incoming(LocalDate.parse("2025-09-01"), new BigDecimal("6"), null, LocalDate.parse("2030-01-01")),
                new Incoming(LocalDate.parse("2025-08-01"), new BigDecimal("7"), null, null)));

        assertThat(jdbc.queryForList("SELECT ref_date || '#' || vintage_no || '=' || coalesce(available_on::text, '-') FROM series_observation "
                + "ORDER BY ref_date, vintage_no", String.class)).containsExactly(
                "2025-08-01#1=-", "2025-08-01#2=-",
                "2025-09-01#1=2030-01-01", "2025-09-01#2=2030-01-01",
                "2025-10-01#1=2025-11-07", "2025-10-01#2=2026-11-19");
    }

    @Test
    void 개정의_저장일은_받은_순간의_UTC_날짜이고_실행_시작과_다를_수_있다() {
        LocalDate lag = LocalDate.parse("2025-11-07");
        writer.write(run, T1, series, Mode.LATEST, FROM, List.of(new Incoming(LocalDate.parse("2025-10-01"), BigDecimal.ONE, null, lag)));
        close(run);
        long second = openRun(T2);

        writer.write(second, T2, Instant.parse("2026-11-20T00:00:30Z"), series, Mode.LATEST, FROM,
                List.of(new Incoming(LocalDate.parse("2025-10-01"), BigDecimal.TEN, null, lag)));
        close(second);
        long third = openRun(Instant.parse("2026-11-21T10:00:00Z"));
        writer.write(third, Instant.parse("2026-11-21T10:00:00Z"), Instant.parse("2026-11-21T16:00:00Z"), series, Mode.LATEST, FROM,
                List.of(new Incoming(LocalDate.parse("2025-10-01"), new BigDecimal("11"), null, lag)));

        assertThat(jdbc.queryForList("SELECT vintage_no || '=' || available_on FROM series_observation ORDER BY vintage_no", String.class))
                .containsExactly("1=2025-11-07", "2=2026-11-20", "3=2026-11-21");
    }

    @Test
    void 최신_값_방식에서_한_날짜에_값이_둘이면_아무것도_쓰지_않는다() {
        assertThatThrownBy(() -> writer.write(run, T1, series, Mode.LATEST, FROM,
                List.of(in("2025-11-16", "1", null), in("2025-11-17", "3.6", null), in("2025-11-17", "3.7", null))))
                .isInstanceOf(VintageMismatchException.class);

        assertThat(count()).as("the first date was not written either").isZero();
    }

    @Test
    void 이력_방식은_저장된_빈티지를_앞부분으로_보고_나머지만_더한다() {
        writer.write(run, T1, series, Mode.HISTORY, FROM, List.of(in("2022-12-01", "300.974", "2023-01-12"), in("2022-12-01", "301.460", "2023-02-10")));
        close(run);
        long second = openRun(T2);

        Result result = writer.write(second, T2, series, Mode.HISTORY, FROM, List.of(
                in("2022-12-01", "301.423", "2024-02-09"), in("2022-12-01", "300.974", "2023-01-12"), in("2022-12-01", "301.460", "2023-02-10")));

        assertThat(result).as("incoming order does not matter").isEqualTo(new Result(1, 1, 2));
        assertThat(jdbc.queryForList("SELECT vintage_no || '@' || realtime_start FROM series_observation ORDER BY vintage_no", String.class))
                .containsExactly("1@2023-01-12", "2@2023-02-10", "3@2024-02-09");
    }

    @Test
    void 이력_방식에서_날짜_없는_첫_빈티지는_날짜_없는_첫_빈티지와_맞는다() {
        writer.write(run, T1, series, Mode.HISTORY, FROM, List.of(in("1990-01-01", "132.3", null)));
        close(run);
        long second = openRun(T2);

        Result result = writer.write(second, T2, series, Mode.HISTORY, FROM, List.of(in("1990-01-01", "132.3", null), in("1990-01-01", "132.1", "2002-02-20")));

        assertThat(result).isEqualTo(new Result(1, 1, 1));
    }

    @Test
    void 이력_방식에서_저장된_빈티지가_바뀌었거나_빠졌거나_날짜가_다르면_그_계열은_아무것도_쓰지_않는다() {
        writer.write(run, T1, series, Mode.HISTORY, FROM, List.of(in("2022-12-01", "300.974", "2023-01-12"), in("2022-12-01", "301.460", "2023-02-10")));
        close(run);
        long second = openRun(T2);
        int before = count();

        for (List<Incoming> bad : List.of(
                List.of(in("2022-12-01", "300.000", "2023-01-12"), in("2022-12-01", "301.460", "2023-02-10")),
                List.of(in("2022-12-01", "300.974", "2023-01-12")),
                List.of(in("2022-12-01", "300.974", "2023-01-13"), in("2022-12-01", "301.460", "2023-02-10")),
                List.of(in("2022-12-01", "300.974", null), in("2022-12-01", "301.460", "2023-02-10")),
                List.of(in("2023-01-01", "9", "2023-02-14"), in("2022-12-01", "300.974", "2023-01-12"), in("2022-12-01", "301.460", "2023-02-10"),
                        in("2022-12-01", "301.5", "2023-02-10")))) {
            assertThatThrownBy(() -> writer.write(second, T2, series, Mode.HISTORY, FROM, bad)).isInstanceOf(VintageMismatchException.class)
                    .hasMessageNotContaining("300").hasMessageNotContaining("301");
            assertThat(count()).as("nothing was written").isEqualTo(before);
        }
    }

    @Test
    void 값이_바뀌었는데_관측일시가_앞서면_시계_오류다() {
        writer.write(run, T2, series, Mode.LATEST, FROM, List.of(in("2025-11-17", "3.6", null)));
        close(run);
        long second = openRun(T1);

        assertThatThrownBy(() -> writer.write(second, T1, series, Mode.LATEST, FROM, List.of(in("2025-11-17", "3.7", null))))
                .isInstanceOf(ClockSkewException.class);
        assertThat(writer.write(second, T1, series, Mode.LATEST, FROM, List.of(in("2025-11-17", "3.6", null)))).as("an unchanged value never skews")
                .isEqualTo(new Result(0, 0, 1));
    }

    @Test
    void 실행이_더_이상_RUNNING이_아니거나_없으면_쓰지_않는다() {
        close(run);

        assertThatThrownBy(() -> writer.write(run, T1, series, Mode.LATEST, FROM, List.of(in("2025-11-17", "3.6", null)))).isInstanceOf(RunLostException.class);
        assertThatThrownBy(() -> writer.write(run + 1000, T1, series, Mode.LATEST, FROM, List.of(in("2025-11-17", "3.6", null))))
                .isInstanceOf(RunLostException.class);
        assertThat(count()).isZero();
    }

    @Test
    void 표_제약이_거절하는_행이_섞이면_계열_전체가_롤백된다() {
        assertThatThrownBy(() -> writer.write(run, T1, series, Mode.HISTORY, FROM,
                List.of(in("2025-11-17", "3.6", "2025-11-18"), in("2025-11-18", "3.7", "2025-11-17"))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count()).isZero();
    }

    @Test
    void 이력_방식에서_저장된_날짜가_답에서_빠지면_아무것도_쓰지_않는다() {
        writer.write(run, T1, series, Mode.HISTORY, FROM, List.of(in("2022-12-01", "300.974", "2023-01-12"), in("2023-01-01", "302.7", "2023-02-14")));
        close(run);
        long second = openRun(T2);

        assertThatThrownBy(() -> writer.write(second, T2, series, Mode.HISTORY, FROM,
                List.of(in("2022-12-01", "300.974", "2023-01-12"), in("2023-02-01", "304.1", "2023-03-14"))))
                .isInstanceOf(VintageMismatchException.class);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void 이력_방식에서_새_빈티지의_관측일시가_앞서면_시계_오류다() {
        writer.write(run, T2, series, Mode.HISTORY, FROM, List.of(in("2022-12-01", "300.974", "2023-01-12")));
        close(run);
        long second = openRun(T1);

        assertThatThrownBy(() -> writer.write(second, T1, series, Mode.HISTORY, FROM,
                List.of(in("2022-12-01", "300.974", "2023-01-12"), in("2022-12-01", "301.460", "2023-02-10"))))
                .isInstanceOf(ClockSkewException.class);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 이력_방식에서_요청_범위_앞쪽_날짜가_사라지면_뒤쪽도_쓰지_않는다() {
        LocalDate from = LocalDate.of(2022, 1, 1);
        writer.write(run, T1, series, Mode.HISTORY, from, List.of(in("2022-12-01", "300.974", "2023-01-12"), in("2023-01-01", "302.7", "2023-02-14")));
        close(run);
        long second = openRun(T2);

        assertThatThrownBy(() -> writer.write(second, T2, series, Mode.HISTORY, from,
                List.of(in("2023-01-01", "302.7", "2023-02-14"), in("2023-02-01", "304.1", "2023-03-14"))))
                .isInstanceOf(VintageMismatchException.class);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void 비어_있는_답은_저장된_날짜가_있으면_어느_쓰기_경로로도_이력_방식에서_실패한다() {
        writer.write(run, T1, series, Mode.HISTORY, FROM, List.of(in("2022-12-01", "300.974", "2023-01-12")));
        close(run);
        long second = openRun(T2);

        assertThatThrownBy(() -> writer.write(second, T2, series, Mode.HISTORY, FROM, List.of())).isInstanceOf(VintageMismatchException.class);
        assertThat(java.util.Arrays.stream(SeriesObservationWriter.class.getMethods()).filter(m -> m.getName().equals("write")).map(m -> m.getParameterCount()))
                .as("every public write path takes the requested start (7 = the same plus the receive time)").containsOnly(6, 7);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 이력_방식에서_답이_통째로_비면_저장된_이력이_있을_때_실패한다() {
        LocalDate from = LocalDate.of(2022, 1, 1);
        writer.write(run, T1, series, Mode.HISTORY, from, List.of(in("2022-12-01", "300.974", "2023-01-12")));
        close(run);
        long second = openRun(T2);

        assertThatThrownBy(() -> writer.write(second, T2, series, Mode.HISTORY, from, List.of())).isInstanceOf(VintageMismatchException.class);
        assertThat(count()).isEqualTo(1);
        assertThat(writer.write(second, T2, series, Mode.HISTORY, LocalDate.of(2030, 1, 1), List.of())).as("nothing stored in range").isEqualTo(new Result(0, 0, 0));
    }

    @Test
    void 이력_방식은_시작일이_없으면_빈_입력도_있는_입력도_쓰기_전에_거절한다() {
        assertThatThrownBy(() -> writer.write(run, T1, series, Mode.HISTORY, null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> writer.write(run, T1, series, Mode.HISTORY, null, List.of(in("2022-12-01", "300.974", "2023-01-12"))))
                .isInstanceOf(NullPointerException.class);
        assertThat(count()).isZero();
    }
}
