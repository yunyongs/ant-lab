package antlab.collect.price;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Window and recovery settings of the daily price job; the KIS client settings are {@code KisProperties}. */
@Validated
@ConfigurationProperties("antlab.collect.kis")
public record PriceJobProperties(
        @Min(0) @DefaultValue("10") int lookbackDays,
        @Min(1) @DefaultValue("30") int initialDays,
        @DefaultValue("2h") Duration staleAfter) {
}
