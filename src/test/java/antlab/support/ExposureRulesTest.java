package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.exposure.ExposedFrom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 검사기 자체 시험: 위반 사례를 각각 실패로 보고하고, 올바른 record 는 통과시킨다(DB 없이 합성 태그로). */
class ExposureRulesTest {

    private static ExposureCatalog catalog() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("asset.id", ExposureCatalog.INTERNAL);
        tags.put("asset.name", ExposureCatalog.EXPOSED);
        tags.put("asset.currency", ExposureCatalog.INTERNAL);
        tags.put("asset.listed_on", ExposureCatalog.REVEAL_ONLY);
        tags.put("month_block.id", ExposureCatalog.INTERNAL);
        tags.put("month_event.month_block_id", ExposureCatalog.EXPOSED);
        tags.put("fx_month.id", ExposureCatalog.INTERNAL);
        tags.put("fx_month.month_block_id", ExposureCatalog.INTERNAL);
        tags.put("asset.asset_group_id", ExposureCatalog.EXPOSED);
        tags.put("turn_feed.asset_id", ExposureCatalog.EXPOSED);
        return new ExposureCatalog(tags);
    }

    record Good(@ExposedFrom("asset.id") Long id, @ExposedFrom("asset.name") String name) {
    }

    record InternalSource(@ExposedFrom("asset.currency") String currency) {
    }

    record RevealOnlySource(@ExposedFrom("asset.listed_on") String listedOn) {
    }

    record NoAnnotation(String name) {
    }

    record DateKey(@ExposedFrom("month_block.id") Long blockId) {
    }

    record BlockFk(@ExposedFrom("month_event.month_block_id") Long blockId) {
    }

    record MonthTableId(@ExposedFrom("fx_month.id") Long id) {
    }

    record GroupFk(@ExposedFrom("asset.asset_group_id") Long groupId) {
    }

    record ExposedFk(@ExposedFrom("turn_feed.asset_id") Long assetId) {
    }

    record UnknownColumn(@ExposedFrom("asset.nothing") String x) {
    }

    record Wrapper(List<Good> items) {
    }

    record WrapperBad(List<InternalSource> items) {
    }

    @Test
    void 올바른_record는_통과한다() {
        assertThat(ExposureRules.check(Good.class, catalog())).isEmpty();
        assertThat(ExposureRules.check(Wrapper.class, catalog())).isEmpty();
    }

    @Test
    void 내부_태그_원천은_실패한다() {
        assertThat(ExposureRules.check(InternalSource.class, catalog())).singleElement().asString()
                .contains("asset.currency").contains("내부");
    }

    @Test
    void 복기_전용_원천은_실패한다() {
        assertThat(ExposureRules.check(RevealOnlySource.class, catalog())).singleElement().asString()
                .contains("복기 전용");
    }

    @Test
    void 애너테이션_없는_구성_요소는_실패한다() {
        assertThat(ExposureRules.check(NoAnnotation.class, catalog())).singleElement().asString()
                .contains("@ExposedFrom 없음");
    }

    @Test
    void 날짜_순서_키는_태그와_상관없이_영구_금지다() {
        assertThat(ExposureRules.check(DateKey.class, catalog())).singleElement().asString().contains("영구 금지");
        assertThat(ExposureRules.check(BlockFk.class, catalog())).singleElement().asString().contains("영구 금지");
    }

    @Test
    void 달_순서로_적재된_표의_id도_금지다() {
        assertThat(ExposureRules.check(MonthTableId.class, catalog())).singleElement().asString()
                .contains("영구 금지").contains("fx_month.id");
    }

    @Test
    void 노출_태그여도_식별자_열은_허용_목록으로만_통과한다() {
        assertThat(ExposureRules.check(GroupFk.class, catalog())).isEmpty();
        assertThat(ExposureRules.check(ExposedFk.class, catalog())).singleElement().asString()
                .contains("식별자 허용 목록에 없음");
    }

    @Test
    void 없는_열을_가리키면_실패한다() {
        assertThat(ExposureRules.check(UnknownColumn.class, catalog())).singleElement().asString().contains("DB 에 없음");
    }

    @Test
    void 중첩_목록의_위반도_잡는다() {
        assertThat(ExposureRules.check(WrapperBad.class, catalog())).hasSize(1);
    }

    @Test
    void 허용_목록과_영구_금지_목록은_겹치지_않는다() {
        assertThat(ExposureRules.IDENTIFIER_ALLOWED).noneMatch(source -> ExposureRules.forbidden(source, catalog()));
    }
}
