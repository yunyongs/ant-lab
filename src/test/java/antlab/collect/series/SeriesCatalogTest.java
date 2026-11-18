package antlab.collect.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SeriesCatalogTest {

    private static final String HEADER = "code,source,source_series_id,unit,frequency,release_lag_days,revisable\n";
    private static final Path MODEL = Path.of("engine/src/antlab_engine/regime/model.py");
    private static final Path ADAPTER = Path.of("engine/src/antlab_engine/regime/adapter.py");
    private static final Path PUBLICATION = Path.of("engine/src/antlab_engine/regime/publication.py");

    private static SeriesDef fred(String code) {
        return SeriesCatalog.fromClasspath().of(SeriesSource.FRED).stream().filter(d -> d.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void 추적_파일은_FRED_아홉_계열이고_ECOS는_아직_없다() {
        SeriesCatalog catalog = SeriesCatalog.fromClasspath();

        assertThat(catalog.of(SeriesSource.FRED)).extracting(SeriesDef::code).containsExactly("US_DGS2", "US_CPILFESL", "US_ICSA",
                "US_SAHM", "US_T10Y3M", "US_DTWEXBGS", "US_VIX", "US_BAA10Y", "FRED_DTWEXM");
        assertThat(catalog.of(SeriesSource.ECOS)).isEmpty();
        assertThat(catalog.series()).extracting(SeriesDef::sourceSeriesId).doesNotHaveDuplicates();
        assertThat(catalog.series().stream().filter(SeriesDef::revisable)).extracting(SeriesDef::code)
                .containsExactly("US_CPILFESL", "US_ICSA");
    }

    @Test
    void 코드는_엔진의_출처_계열_이름이고_빈도_수정_여부_시차는_엔진_정의와_같다() throws IOException {
        String adapter = Files.readString(ADAPTER);
        Matcher sourceNames = Pattern.compile("^    (\\w+) = \"(\\w+)\"", Pattern.MULTILINE)
                .matcher(adapter.substring(adapter.indexOf("class SourceSeries"), adapter.indexOf("SOURCE_TO_CODE")));
        List<String> engineSources = new java.util.ArrayList<>();
        while (sourceNames.find()) {
            assertThat(sourceNames.group(2)).isEqualTo(sourceNames.group(1));
            engineSources.add(sourceNames.group(1));
        }
        String model = Files.readString(MODEL);
        Map<String, String> fredIdOfCode = new java.util.HashMap<>();
        Matcher ids = Pattern.compile("^    (\\w+) = \"\\w+\"\\s+# FRED (\\w+)", Pattern.MULTILINE).matcher(model);
        while (ids.find()) {
            fredIdOfCode.put(ids.group(1), ids.group(2));
        }
        Map<String, String[]> specs = new java.util.HashMap<>();
        Matcher spec = Pattern.compile("SeriesCode\\.(\\w+): SeriesSpec\\(Frequency\\.(\\w+), Market\\.\\w+(.*)\\),\\s*$", Pattern.MULTILINE)
                .matcher(model);
        while (spec.find()) {
            specs.put(spec.group(1), new String[] {spec.group(2), spec.group(3)});
        }
        String publication = Files.readString(PUBLICATION);
        Map<String, Integer> lags = Map.of(
                "US_ICSA", constant(publication, "ICSA_LAG_DAYS"),
                "US_CPILFESL", constant(publication, "CPI_LAG_DAYS"),
                "US_SAHM", constant(publication, "SAHM_LAG_DAYS"),
                "US_DGS2", 1, "US_T10Y3M", 1, "US_BAA10Y", 1, "US_VIX", 1);

        for (SeriesDef def : SeriesCatalog.fromClasspath().of(SeriesSource.FRED)) {
            assertThat(engineSources).as(def.code() + " is a SourceSeries").contains(def.code());
            if (def.code().equals("FRED_DTWEXM")) {
                assertThat(adapter).contains("FRED_DTWEXM = \"FRED_DTWEXM\"  # FRED DTWEXM");
                assertThat(def.sourceSeriesId()).isEqualTo("DTWEXM");
                continue;
            }
            assertThat(fredIdOfCode.get(def.code())).as(def.code() + " FRED id in the engine").isEqualTo(def.sourceSeriesId());
            String[] engineSpec = specs.get(def.code());
            assertThat(engineSpec).as(def.code() + " has an engine spec").isNotNull();
            assertThat(def.frequency().name()).as(def.code() + " frequency").isEqualTo(engineSpec[0]);
            assertThat(def.revisable()).as(def.code() + " revisable").isEqualTo(engineSpec[1].contains("revisable=True"));
            if (lags.containsKey(def.code())) {
                assertThat(def.releaseLagDays()).as(def.code() + " release lag").isEqualTo(lags.get(def.code()));
            }
        }
    }

    private static int constant(String source, String name) {
        Matcher m = Pattern.compile("^" + name + " = (\\d+)", Pattern.MULTILINE).matcher(source);
        assertThat(m.find()).as(name).isTrue();
        return Integer.parseInt(m.group(1));
    }

    @Test
    void 머리글이_다르거나_칸_수가_틀리거나_코드가_겹치거나_플래그가_틀리면_거절한다() {
        assertThatThrownBy(() -> SeriesCatalog.fromCsv("code,source\n")).hasMessageContaining("header");
        assertThatThrownBy(() -> SeriesCatalog.fromCsv("")).hasMessageContaining("header");
        assertThatThrownBy(() -> SeriesCatalog.fromCsv(HEADER + "A,FRED,X,INDEX,DAILY,1\n")).hasMessageContaining("7 cells");
        assertThatThrownBy(() -> SeriesCatalog.fromCsv(HEADER + "A,FRED,X,INDEX,DAILY,1,false\nA,FRED,Y,INDEX,DAILY,1,false\n"))
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> SeriesCatalog.fromCsv(HEADER + "A,FRED,X,INDEX,DAILY,1,yes\n")).hasMessageContaining("true or false");
        assertThatThrownBy(() -> SeriesCatalog.fromCsv(HEADER + "A,FRED,X,INDEX,HOURLY,1,false\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeriesCatalog.fromCsv(HEADER + "A,WB,X,INDEX,DAILY,1,false\n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 두_발표_주기보다_오래됐다는_판정은_빈도마다_경계가_다르다() {
        LocalDate first = LocalDate.of(2023, 1, 12);

        assertThat(SeriesFrequency.MONTHLY.olderThanTwoPeriods(LocalDate.of(2022, 11, 1), first)).as("Nov, first seen Jan 12").isTrue();
        assertThat(SeriesFrequency.MONTHLY.olderThanTwoPeriods(LocalDate.of(2022, 12, 1), first)).as("Dec is the previous month").isFalse();
        assertThat(SeriesFrequency.MONTHLY.olderThanTwoPeriods(LocalDate.of(2022, 11, 12), first)).as("exactly two months: not more").isFalse();
        assertThat(SeriesFrequency.MONTHLY.olderThanTwoPeriods(LocalDate.of(2022, 11, 11), first)).isTrue();
        assertThat(SeriesFrequency.WEEKLY.olderThanTwoPeriods(LocalDate.of(2022, 12, 31), first)).isFalse();
        assertThat(SeriesFrequency.WEEKLY.olderThanTwoPeriods(LocalDate.of(2022, 12, 24), first)).isTrue();
        assertThat(SeriesFrequency.WEEKLY.olderThanTwoPeriods(LocalDate.of(2022, 12, 29), first)).as("exactly two weeks: not more").isFalse();
        assertThat(SeriesFrequency.DAILY.olderThanTwoPeriods(LocalDate.of(2023, 1, 10), first)).isFalse();
        assertThat(SeriesFrequency.DAILY.olderThanTwoPeriods(LocalDate.of(2023, 1, 9), first)).isTrue();
    }

    @Test
    void 카탈로그_행이_표_정의와_같은_모양이다() {
        SeriesDef cpi = fred("US_CPILFESL");

        assertThat(cpi).isEqualTo(new SeriesDef("US_CPILFESL", SeriesSource.FRED, "CPILFESL", "INDEX", SeriesFrequency.MONTHLY, 10, true));
    }
}
