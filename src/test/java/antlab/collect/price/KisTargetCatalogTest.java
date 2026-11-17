package antlab.collect.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.PriceBasis;
import antlab.collect.kis.KisExchange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The tracked target list, checked against the tracked sample asset list: no price value lives in either file. */
class KisTargetCatalogTest {

    /** (market:ticker) to asset_type of every row in db/sample/asset.csv. */
    private static Map<String, String> sampleAssets() throws IOException {
        return Files.readAllLines(Path.of("db/sample/asset.csv")).stream().skip(1).filter(l -> !l.isBlank())
                .map(l -> l.split(",", -1)).collect(Collectors.toMap(c -> c[0] + ":" + c[1], c -> c[3]));
    }

    @Test
    void 추적되는_목록은_표본_종목_20개를_모두_덮고_가격_기준이_출처_규칙과_같다() throws IOException {
        List<KisTarget> targets = KisTargetCatalog.fromClasspath().targets();
        Map<String, String> assets = sampleAssets();

        assertThat(assets).hasSize(20);
        assertThat(targets).extracting(KisTarget::key).containsExactlyInAnyOrderElementsOf(assets.keySet());
        for (KisTarget target : targets) {
            boolean krEtf = "KR".equals(target.market()) && "ETF".equals(assets.get(target.key()));
            assertThat(target.basis()).as(target.key()).isEqualTo(krEtf ? PriceBasis.RAW : PriceBasis.ADJUSTED);
            if ("KR".equals(target.market())) {
                assertThat(target.exchangeCode()).as(target.key()).isEmpty();
            } else {
                assertThat(KisExchange.valueOf(target.exchangeCode())).as(target.key()).isNotNull();
            }
        }
    }

    @Test
    void 거래소_코드는_tools_fetch가_실측한_값과_같다() {
        Map<String, String> measured = Map.of("AAPL", "NAS", "MSFT", "NAS", "NVDA", "NAS", "QQQ", "NAS", "TLT", "NAS",
                "SHY", "NAS", "JNJ", "NYS", "KO", "NYS", "XOM", "NYS", "SPY", "AMS");
        Map<String, String> listed = KisTargetCatalog.fromClasspath().targets().stream().filter(t -> "US".equals(t.market()))
                .collect(Collectors.toMap(KisTarget::ticker, KisTarget::exchangeCode));

        assertThat(listed).isEqualTo(measured);
    }

    @Test
    void 파일에는_가격_모양의_값이_없다() throws IOException {
        for (String line : Files.readAllLines(Path.of("src/main/resources/collect/kis-targets.csv")).stream().skip(1).toList()) {
            assertThat(line).matches("(KR|US),[A-Z0-9]+,(NAS|NYS|AMS)?,(ADJUSTED|RAW)");
        }
    }

    @Test
    void 머리글이_다르거나_칸_수가_다르거나_기준이_모르는_값이면_읽지_않는다() {
        assertThatThrownBy(() -> KisTargetCatalog.fromCsv("a,b\nKR,1,,RAW")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KisTargetCatalog.fromCsv("market,ticker,exchange_code,price_basis\nKR,1,RAW"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KisTargetCatalog.fromCsv("market,ticker,exchange_code,price_basis\nKR,1,,adjusted"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 대상_필터는_시장_티커_모양만_받는다() {
        assertThat(TargetFilter.parse("").isAll()).isTrue();
        assertThat(TargetFilter.parse(null).isAll()).isTrue();
        assertThat(TargetFilter.parse("KR:005930, US:AAPL").keys()).containsExactlyInAnyOrder("KR:005930", "US:AAPL");
        for (String bad : List.of("005930", "JP:7203", "KR:", "KR:005930;US:AAPL", "KR:../x", ",", ",,", "KR:005930,", ",KR:005930", "KR:005930,,US:AAPL")) {
            assertThatThrownBy(() -> TargetFilter.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
