package antlab.api.report;

import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Shared calls of the season analysis tests. */
final class SeasonAnalysisApiSupport {

    private SeasonAnalysisApiSupport() {
    }

    static MockHttpServletResponse getAnalysis(MockMvcTester mvc, String seasonId) {
        return mvc.get().uri("/api/v1/seasons/" + seasonId + "/report/analysis").exchange().getResponse();
    }

    static MockHttpServletResponse getAnalysis(MockMvcTester mvc, long seasonId) {
        return getAnalysis(mvc, Long.toString(seasonId));
    }
}
