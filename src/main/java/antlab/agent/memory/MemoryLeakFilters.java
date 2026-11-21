package antlab.agent.memory;

import antlab.history.MonthEventRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the authoritative {@link MemoryLeakFilter}: the owner-editable terms file read as bytes (its hash is the
 * filter version) plus every {@code month_event} title. The titles are read on each call, so a re-loaded tag set takes
 * effect without a restart.
 */
@Component
public class MemoryLeakFilters {

    static final String TERMS_RESOURCE = "agent/leak-terms.txt";

    private final byte[] termsFile;
    private final MonthEventRepository events;

    public MemoryLeakFilters(MonthEventRepository events) {
        this.events = events;
        try (InputStream in = new ClassPathResource(TERMS_RESOURCE).getInputStream()) {
            this.termsFile = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Transactional(readOnly = true)
    public MemoryLeakFilter current() {
        return new MemoryLeakFilter(MemoryLeakFilter.loadTerms(termsFile, events.findRevealedTitles()));
    }
}
