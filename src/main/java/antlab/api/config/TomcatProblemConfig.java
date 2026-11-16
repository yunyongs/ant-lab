package antlab.api.config;

import antlab.api.error.ProblemErrorReportValve;
import org.apache.catalina.Container;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Swaps Tomcat's HTML error report for {@link ProblemErrorReportValve}. Boot adds its own plain ErrorReportValve to the
 * Host pipeline from a context customizer, so this customizer runs last: it drops that valve and names ours as the
 * Host's error report valve class (otherwise the Host would add a default one again).
 */
@Configuration(proxyBeanMethods = false)
public class TomcatProblemConfig {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> problemErrorReportValve() {
        return factory -> factory.addContextCustomizers(context -> {
            Container host = context.getParent();
            for (Valve valve : host.getPipeline().getValves()) {
                if (valve.getClass() == ErrorReportValve.class) {
                    host.getPipeline().removeValve(valve);
                }
            }
            if (host instanceof StandardHost standardHost) {
                standardHost.setErrorReportValveClass(ProblemErrorReportValve.class.getName());
            }
            ProblemErrorReportValve problemValve = new ProblemErrorReportValve();
            // keep Boot's settings for the statuses this valve leaves to Tomcat: no server version, no stack trace
            problemValve.setShowServerInfo(false);
            problemValve.setShowReport(false);
            host.getPipeline().addValve(problemValve);
        });
    }
}
