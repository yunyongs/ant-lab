package antlab.common.exposure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 응답 record 구성 요소가 어느 DB 열에서 오는지 적는다({@code "표.열"}). 파생 값은 원천 열을 모두 적는다.
 * 노출 상한 시험(ExposureContractTest)이 이 열의 DB 코멘트 태그가 {@code [노출]} 인지 확인한다(SPRING_JPA §4.6).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface ExposedFrom {

    String[] value();
}
