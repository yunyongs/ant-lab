package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** 정의 문자열 파서 자체 시험(합성 정의). */
class CheckConstraintCatalogTest {

    @Test
    void 여러_값_목록을_읽는다() {
        String def = "CHECK (((market)::text = ANY ((ARRAY['KR'::character varying, 'US'::character varying])::text[])))";
        assertThat(CheckConstraintCatalog.parseValues(def)).containsExactly("KR", "US");
    }

    @Test
    void 값이_하나인_등호_꼴도_읽는다() {
        assertThat(CheckConstraintCatalog.parseValues("CHECK (((currency)::text = 'USD'::text))"))
                .containsExactly("USD");
    }

    @Test
    void 작은따옴표_이스케이프를_푼다() {
        assertThat(CheckConstraintCatalog.parseValues("CHECK ((x = ANY (ARRAY['A''B'::text, 'C'::text])))"))
                .containsExactly("A'B", "C");
    }

    @Test
    void 정규식_검사와_이름이_다른_제약은_값_목록이_아니다() {
        Set<String> columns = Set.of("market", "currency", "ticker");
        assertThat(CheckConstraintCatalog.isValueList("asset", "ck_asset_ticker",
                columns, "CHECK (((ticker)::text ~ '^[A-Z]'::text))")).isFalse();
        assertThat(CheckConstraintCatalog.isValueList("asset", "ck_asset_market_currency", columns,
                "CHECK (((market)::text = 'KR'::text))")).isFalse();
        assertThat(CheckConstraintCatalog.isValueList("asset", "ck_asset_market", columns,
                "CHECK (((market)::text = 'KR'::text))")).isTrue();
    }

    @Test
    void 다른_열이나_조건이_섞인_제약은_값_목록이_아니다() {
        Set<String> columns = Set.of("type", "limit_price", "kind", "category_hidden", "repeat_count", "side");
        assertThat(CheckConstraintCatalog.isValueList("orders", "ck_orders_limit_price", columns,
                "CHECK ((((type)::text = 'MARKET'::text) AND (limit_price IS NULL)) OR (((type)::text = 'LIMIT'::text) AND (limit_price > (0)::numeric)))"))
                .isFalse();
        assertThat(CheckConstraintCatalog.isValueList("turn_feed", "ck_turn_feed_category_hidden", columns,
                "CHECK (((NOT category_hidden) OR ((kind)::text = 'EVENT'::text)))")).isFalse();
        assertThat(CheckConstraintCatalog.isValueList("turn_feed", "ck_turn_feed_repeat_count", columns,
                "CHECK (((repeat_count >= 0) AND (((kind)::text <> 'ECHO'::text) OR (repeat_count >= 1))))")).isFalse();
        assertThat(CheckConstraintCatalog.isValueList("orders", "ck_orders_side", columns,
                "CHECK (((side)::text = ANY ((ARRAY['BUY'::character varying, 'SELL'::character varying])::text[])))"))
                .isTrue();
    }
}
