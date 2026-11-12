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
        tags.put("season_path.month_block_id", ExposureCatalog.REVEAL_ONLY);
        tags.put("lineup.declared_position", ExposureCatalog.EXPOSED);
        tags.put("reconciliation_log.status", ExposureCatalog.INTERNAL);
        tags.put("reconciliation_log.turn_no", ExposureCatalog.INTERNAL);
        tags.put("reconciliation_log.attempt_no", ExposureCatalog.INTERNAL);
        tags.put("reconciliation_log.detail", ExposureCatalog.INTERNAL);
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

    record FinishedOnlyCount(
            @ExposedFrom({"season_path.month_block_id", "lineup.declared_position"}) Integer fit) {
    }

    /** 블록 번호를 그대로 내보내는 형 — 표지가 있어도 실패해야 한다. */
    record FinishedOnlyId(@ExposedFrom("season_path.month_block_id") Long monthBlockId) {
    }

    /** 단독 원천인 정수 — 경로 순서 자체이므로 실패해야 한다. */
    record FinishedOnlyAlone(@ExposedFrom("season_path.month_block_id") Integer x) {
    }

    /** 다른 원천과 함께여도 형이 문자열이면 실패해야 한다. */
    record FinishedOnlyText(@ExposedFrom({"season_path.month_block_id", "lineup.declared_position"}) String x) {
    }

    record ReconCounts(@ExposedFrom("reconciliation_log.status") Integer mismatchRows,
            @ExposedFrom({"reconciliation_log.turn_no", "reconciliation_log.attempt_no"}) Integer bundles) {
    }

    record ReconText(@ExposedFrom("reconciliation_log.status") String status) {
    }

    record ReconLong(@ExposedFrom("reconciliation_log.turn_no") long turns) {
    }

    record ReconDetail(@ExposedFrom("reconciliation_log.detail") Integer x) {
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

    @Test
    void 끝난_시즌_전용_원천은_표지가_있는_핸들러에서만_통과한다() {
        assertThat(ExposureRules.check(FinishedOnlyCount.class, catalog(), true)).isEmpty();
        assertThat(ExposureRules.check(FinishedOnlyCount.class, catalog(), false)).singleElement().asString()
                .contains("season_path.month_block_id").contains("@FinishedSeasonOnly");
        // 표지 없는 기본 검사는 예전처럼 영구 금지가 아니라도 실패한다
        assertThat(ExposureRules.check(FinishedOnlyCount.class, catalog())).hasSize(1);
    }

    @Test
    void 끝난_시즌_전용_원천은_표지가_있어도_단독이거나_수치가_아니면_실패한다() {
        String rule = "다른 원천과 함께 집계한 BigDecimal·Integer 값에만 쓸 수 있음";
        assertThat(ExposureRules.check(FinishedOnlyId.class, catalog(), true)).anySatisfy(m -> assertThat(m).contains(rule));
        assertThat(ExposureRules.check(FinishedOnlyAlone.class, catalog(), true)).singleElement().asString().contains(rule);
        assertThat(ExposureRules.check(FinishedOnlyText.class, catalog(), true)).anySatisfy(m -> assertThat(m).contains(rule));
    }

    @Test
    void 건수_전용_원천은_표지와_Integer_형이_모두_있어야_통과한다() {
        assertThat(ExposureRules.check(ReconCounts.class, catalog(), true)).isEmpty();
        assertThat(ExposureRules.check(ReconCounts.class, catalog(), false)).hasSize(3);
        assertThat(ExposureRules.check(ReconText.class, catalog(), true)).singleElement().asString()
                .contains("Integer 가 아님").contains("String");
        assertThat(ExposureRules.check(ReconLong.class, catalog(), true)).singleElement().asString()
                .contains("Integer 가 아님");
    }

    @Test
    void 허용_목록에_없는_대사_열은_표지가_있어도_실패한다() {
        assertThat(ExposureRules.check(ReconDetail.class, catalog(), true)).singleElement().asString()
                .contains("reconciliation_log.detail").contains("내부");
    }

    @Test
    void 확장_허용_목록은_영구_금지_목록과_식별자_허용_목록과_겹치지_않는다() {
        assertThat(ExposureRules.FINISHED_ONLY_ALLOWED).doesNotContainAnyElementsOf(ExposureRules.FORBIDDEN)
                .doesNotContainAnyElementsOf(ExposureRules.IDENTIFIER_ALLOWED);
        assertThat(ExposureRules.COUNT_ONLY_ALLOWED).doesNotContainAnyElementsOf(ExposureRules.FORBIDDEN)
                .doesNotContainAnyElementsOf(ExposureRules.IDENTIFIER_ALLOWED);
    }
}
