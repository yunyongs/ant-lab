package antlab.api.formation;

import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** 다른 패키지의 시험이 전술 변경 요청 도구를 쓰도록 여는 얇은 출입구(도구 본체는 패키지 전용으로 둔다). */
public final class FormationApiSupportAccess {

    public static final int[] A = FormationApiSupport.A;
    public static final int[] B = FormationApiSupport.B;
    public static final int[] C = FormationApiSupport.C;
    public static final int[] D = FormationApiSupport.D;

    private FormationApiSupportAccess() {
    }

    public static MockHttpServletResponse change(MockMvcTester mvc, long season, int turn, int[] formation) {
        return FormationApiSupport.change(mvc, season, turn, formation);
    }
}
