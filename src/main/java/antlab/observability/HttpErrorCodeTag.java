package antlab.observability;

import antlab.common.error.ErrorCode;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Adds the {@code error_code} tag to the HTTP server request metrics. The value is the {@link ErrorCode} name the error
 * handler put on the request, {@code none} for a response below 400, and {@code other} for anything else, so the tag
 * has a fixed set of values whatever a request does. The tag is always present because one meter name needs one tag set.
 */
@Component
public class HttpErrorCodeTag implements ObservationFilter {

    /** Request attribute the error handler sets with the code name of the problem response. */
    public static final String ATTRIBUTE = "antlab.error.code";
    public static final String KEY = "error_code";
    static final String NONE = "none";
    static final String OTHER = "other";
    private static final String HTTP_SERVER = "http.server.requests";
    private static final Set<String> CODES =
            Arrays.stream(ErrorCode.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    @Override
    public Observation.Context map(Observation.Context context) {
        if (HTTP_SERVER.equals(context.getName()) && context instanceof ServerRequestObservationContext http) {
            HttpServletRequest request = http.getCarrier();
            Object attribute = request == null ? null : request.getAttribute(ATTRIBUTE);
            int status = http.getResponse() == null ? 0 : http.getResponse().getStatus();
            context.addLowCardinalityKeyValue(KeyValue.of(KEY, valueOf(attribute, status)));
        }
        return context;
    }

    static String valueOf(Object attribute, int status) {
        if (attribute instanceof String name) {
            return CODES.contains(name) ? name : OTHER;
        }
        return status >= 400 ? OTHER : NONE;
    }
}
