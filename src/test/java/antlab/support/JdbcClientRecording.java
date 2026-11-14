package antlab.support;

import java.lang.reflect.Proxy;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wraps the application's {@link JdbcClient} so every {@code sql(String)} call is reported to {@link SqlRecorder}.
 * Hibernate statements are seen by the statement inspector; this covers the other path. A statement is counted when it
 * is declared, once per execution of the calling method.
 */
@TestConfiguration(proxyBeanMethods = false)
public class JdbcClientRecording {

    @Bean
    static BeanPostProcessor jdbcClientRecorder() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof JdbcClient delegate)) {
                    return bean;
                }
                return Proxy.newProxyInstance(JdbcClient.class.getClassLoader(), new Class<?>[] {JdbcClient.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("sql") && args != null && args.length == 1
                                    && args[0] instanceof String sql) {
                                SqlRecorder.recordJdbcClient(sql);
                            }
                            try {
                                return method.invoke(delegate, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
    }
}
