package antlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/** my Fund Manager API 진입점. 쓰는 패키지만 하위에 둔다(SPRING_JPA §2). */
// No generated password and no in-memory user: members are authenticated from the member table (SEC-03).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class AntlabApplication {

    public static void main(String[] args) {
        SpringApplication.run(AntlabApplication.class, args);
    }
}
