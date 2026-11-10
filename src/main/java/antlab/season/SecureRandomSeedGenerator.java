package antlab.season;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class SecureRandomSeedGenerator implements SeedGenerator {

    private final SecureRandom random = new SecureRandom();

    @Override
    public long nextSeed() {
        return random.nextLong();
    }
}
