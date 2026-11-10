package antlab.api.dev;

import antlab.scoring.ManualReconciliationDto;
import antlab.scoring.ManualReconciliationService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발 도구: 시즌 대사 수동 검사. {@code local} 프로필에서만(`prod`와 함께 켜면 만들지 않는다) 빈이 만들어져 다른 프로필(test·기본·prod)에는 경로 자체가 없다.
 * 접근 제한: 로컬 주소({@code server.address: 127.0.0.1})에만 열리고, 고정 헤더 {@value #DEV_HEADER}: {@value #DEV_HEADER_VALUE} 가 없으면 403 {@code FORBIDDEN} 이다.
 * 응답에는 규칙별 기대·실제·차이·detail 값까지 담으므로 노출 상한 검사에서 제외한다(local 전용, prod 미등록).
 * 공개 계약이 아니라 {@code openapi.yaml} 에 넣지 않고 Swagger UI 에서도 숨긴다. 호출 한 번이 새 시도 번호로 15행을 기록한다.
 */
@RestController
@Profile("local & !prod")
@Hidden
public class DevReconciliationController {

    /** 개발 엔드포인트 관문 헤더 — 이름·값은 이 한 곳에서만 정한다. */
    public static final String DEV_HEADER = "X-Antlab-Dev";
    public static final String DEV_HEADER_VALUE = "true";

    private final ManualReconciliationService manual;

    public DevReconciliationController(ManualReconciliationService manual) {
        this.manual = manual;
    }

    @PostMapping(value = "/api/v1/dev/seasons/{seasonId}/reconciliation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ManualReconciliationDto run(@PathVariable Long seasonId,
            @RequestHeader(value = DEV_HEADER, required = false) String devHeader) {
        if (!DEV_HEADER_VALUE.equals(devHeader)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "개발용 헤더가 없다");
        }
        return manual.run(seasonId);
    }
}
