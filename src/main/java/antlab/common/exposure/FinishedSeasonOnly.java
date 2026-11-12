package antlab.common.exposure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 끝난 시즌에서만 답하는 핸들러의 표지. 응답이 {@code [복기 전용]} 원천(예: {@code season_path.month_block_id}, 경로의 달 순서)에서
 * 나온 집계 값을 담는 핸들러는 이 표지를 달아야 하고, 진행 중 시즌에는 409 {@code SEASON_NOT_FINISHED} 로 답해야 한다.
 * 노출 상한 시험(ExposureContractTest)이 두 가지를 모두 확인한다(SPRING_JPA §4.6).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FinishedSeasonOnly {
}
