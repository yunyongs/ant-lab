package antlab.feed;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The generator is a plain class with no Spring dependency (its input type is the only data door), so it is wired here. */
@Configuration(proxyBeanMethods = false)
public class FeedConfiguration {

    @Bean
    FeedGenerator feedGenerator(FeedTemplateCatalog catalog) {
        return new FeedGenerator(catalog);
    }

    @Bean
    AlertGenerator alertGenerator(FeedTemplateCatalog catalog) {
        return new AlertGenerator(catalog);
    }
}
