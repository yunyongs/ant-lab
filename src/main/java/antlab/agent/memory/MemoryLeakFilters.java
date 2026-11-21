package antlab.agent.memory;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.history.MonthEventRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the authoritative {@link MemoryLeakFilter}: the owner-editable terms file read as bytes (its hash is the
 * filter version) plus every {@code month_event} title. The titles are read on each call, so a re-loaded tag set takes
 * effect without a restart. Without a single title the event-name defence would be the curated list alone, so outside the
 * tests the filter refuses to run ({@code antlab.agent.memory.require-event-titles}).
 */
@Component
public class MemoryLeakFilters {

    static final String TERMS_RESOURCE = "agent/leak-terms.txt";

    private final byte[] termsFile;
    private final MonthEventRepository events;
    private final boolean requireEventTitles;

    public MemoryLeakFilters(MonthEventRepository events,
            @Value("${antlab.agent.memory.require-event-titles:true}") boolean requireEventTitles) {
        this.events = events;
        this.requireEventTitles = requireEventTitles;
        try (InputStream in = new ClassPathResource(TERMS_RESOURCE).getInputStream()) {
            this.termsFile = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Transactional(readOnly = true)
    public MemoryLeakFilter current() {
        List<String> titles = events.findRevealedTitles();
        if (requireEventTitles && titles.isEmpty()) {
            throw new DomainException(ErrorCode.POOL_NOT_LOADED, "사건 제목이 적재되지 않아 기억을 걸러낼 수 없다");
        }
        return new MemoryLeakFilter(MemoryLeakFilter.loadTerms(termsFile, titles));
    }

    /** The filter version label (from the terms file alone), for rows that run no filter. */
    public String version() {
        return MemoryLeakFilter.loadTerms(termsFile, List.of()).version();
    }
}
