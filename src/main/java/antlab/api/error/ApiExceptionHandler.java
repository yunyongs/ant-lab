package antlab.api.error;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 오류를 RFC 9457 {@code application/problem+json} 으로 내보낸다. 최상위에 {@code code} 를 단다.
 * Spring MVC 가 던지는 예외는 {@link ResponseEntityExceptionHandler} 가 만든 ProblemDetail 에
 * code·고정 문장·errors[] 를 덧붙이는 한 곳({@link #handleExceptionInternal})에서 다룬다.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Object> handleDomain(DomainException ex, WebRequest request) {
        HttpStatus status = ErrorHttpMapping.status(ex.getCode());
        if (status.is5xxServerError()) {
            // 5xx 는 응답에 내용을 싣지 않으므로 로그에 남긴다.
            log.error("도메인 예외(5xx)", ex);
        }
        return handleExceptionInternal(ex, null, new HttpHeaders(), status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        // 예외 내용은 서버 로그에만 남기고 응답에는 싣지 않는다.
        log.error("처리되지 않은 예외", ex);
        return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /** 응답 직렬화 실패(예: 출력 자릿수 상한 초과)는 서버 버그다. 기본 처리기는 로그를 남기지 않으므로 여기서 남긴다. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotWritable(
            HttpMessageNotWritableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.error("응답 직렬화 실패", ex);
        return super.handleHttpMessageNotWritable(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail pd ? pd : ProblemDetail.forStatus(statusCode);
        ErrorCode code = ex instanceof DomainException de ? de.getCode() : ErrorHttpMapping.code(statusCode);
        problem.setDetail(detailOf(code, ex));
        problem.setProperty("code", code.name());
        List<Map<String, String>> errors = errorsOf(ex);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    /** 고정 문장. 4xx 도메인 예외만 던진 쪽이 정한 문장을 쓴다(거부된 값은 넣지 않는 규칙을 기능이 지킨다). 5xx 는 언제나 고정 문장. */
    private static String detailOf(ErrorCode code, Exception ex) {
        if (ex instanceof DomainException && !ErrorHttpMapping.status(code).is5xxServerError()) {
            return ex.getMessage();
        }
        return switch (code) {
            case INVALID_ARGUMENT -> "요청을 처리할 수 없다. 입력을 확인한다";
            case NOT_FOUND -> "대상을 찾을 수 없다";
            case METHOD_NOT_ALLOWED -> "지원하지 않는 메서드다";
            case NOT_ACCEPTABLE -> "요청한 응답 형식을 제공하지 않는다";
            case UNSUPPORTED_MEDIA_TYPE -> "지원하지 않는 본문 형식이다";
            case INTERNAL_ERROR -> "처리 중 오류가 났다";
            // 4xx 도메인 코드는 던진 쪽 문장을 쓰므로 여기에 오지 않는다(위 분기). 기본 분기를 두지 않아 코드 추가 시 컴파일이 막는다.
            case POOL_NOT_LOADED, POOL_NOT_JUDGED, PATH_TOO_LONG, SEASON_LIMIT, TURN_NOT_REACHED,
                    SEASON_NOT_ACTIVE, TURN_MISMATCH, LEDGER_MISMATCH, LINEUP_MISMATCH, ASSET_NOT_IN_POOL,
                    INSUFFICIENT_CASH, POSITION_CONFLICT, POSITION_REQUIRED, RECONCILIATION_FAILED, FORBIDDEN -> "요청을 처리할 수 없는 상태다";
            // 5xx 는 언제나 이 고정 문장이다(내용은 로그에만).
            case MEMBER_UNAVAILABLE -> "지금은 이 기능을 쓸 수 없다";
        };
    }

    /** {@code @Valid} 위반 목록. 필드 경로와 제약 이름만 싣고 거부된 값은 싣지 않는다. */
    private static List<Map<String, String>> errorsOf(Exception ex) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (ex instanceof MethodArgumentNotValidException manve) {
            manve.getBindingResult().getAllErrors().forEach(e -> errors.add(entry(e, fieldOf(e, null))));
        } else if (ex instanceof HandlerMethodValidationException hmve) {
            hmve.getParameterValidationResults().forEach(result -> {
                String parameter = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(e -> errors.add(entry(e, fieldOf(e, parameter))));
            });
        }
        return errors;
    }

    private static String fieldOf(MessageSourceResolvable error, String fallback) {
        if (error instanceof FieldError fe) {
            return fe.getField();
        }
        return fallback == null ? "" : fallback;
    }

    private static Map<String, String> entry(MessageSourceResolvable error, String field) {
        // 코드 목록의 마지막이 제약 애너테이션 단순 이름이다(예: Min).
        String[] codes = error.getCodes();
        String reason = codes == null || codes.length == 0 ? "Invalid" : codes[codes.length - 1];
        return Map.of("field", field, "reason", reason);
    }
}
