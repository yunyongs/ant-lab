package antlab.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import org.springframework.security.web.firewall.FirewalledRequest;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.stereotype.Component;

/**
 * The strict firewall checks header names and values only when a header is read, so a bad header can be rejected deep
 * inside a filter or Spring MVC, where the framework logs the exception (with the header value) at DEBUG. Reading every
 * header once here rejects it before anything else sees the request, so the rejection handler is the only place that
 * answers it. Request parameters stay checked lazily; their values are not restricted by the strict firewall anyway.
 */
@Component
class EagerHeaderFirewall implements HttpFirewall {

    private final StrictHttpFirewall strict = new StrictHttpFirewall();

    @Override
    public FirewalledRequest getFirewalledRequest(HttpServletRequest request) {
        FirewalledRequest firewalled = strict.getFirewalledRequest(request);
        for (String name : Collections.list(firewalled.getHeaderNames())) {
            // the call throws RequestRejectedException on a bad name or value; the result is not needed
            Collections.list(firewalled.getHeaders(name));
        }
        return firewalled;
    }

    @Override
    public HttpServletResponse getFirewalledResponse(HttpServletResponse response) {
        return strict.getFirewalledResponse(response);
    }
}
