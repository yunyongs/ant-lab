package antlab.api.dev;

import antlab.scoring.ManualReconciliationDto;
import antlab.scoring.ManualReconciliationService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발 도구: 시즌 대사 수동 검사. {@code local} 프로필에서만(`prod`와 함께 켜면 만들지 않는다) 빈이 만들어져 다른 프로필(test·기본·prod)에는 경로 자체가 없다.
 * 공개 계약이 아니라 {@code openapi.yaml} 에 넣지 않고 Swagger UI 에서도 숨긴다. 호출 한 번이 새 시도 번호로 15행을 기록한다.
 */
@RestController
@Profile("local & !prod")
@Hidden
public class DevReconciliationController {

    private final ManualReconciliationService manual;

    public DevReconciliationController(ManualReconciliationService manual) {
        this.manual = manual;
    }

    @PostMapping(value = "/api/v1/dev/seasons/{seasonId}/reconciliation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ManualReconciliationDto run(@PathVariable Long seasonId) {
        return manual.run(seasonId);
    }
}
