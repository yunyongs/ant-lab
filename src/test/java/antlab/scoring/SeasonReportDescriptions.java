package antlab.scoring;

import antlab.api.report.SeasonReportController;
import io.swagger.v3.oas.annotations.Operation;

/** 시험 도우미: 컨트롤러 {@code @Operation} 설명 문자열을 읽는다. */
final class SeasonReportDescriptions {

    private SeasonReportDescriptions() {
    }

    static String controllerDescription() throws Exception {
        return SeasonReportController.class.getMethod("get", Long.class).getAnnotation(Operation.class).description();
    }
}
