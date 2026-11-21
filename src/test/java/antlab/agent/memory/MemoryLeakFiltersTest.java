package antlab.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.history.MonthEventRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Outside the tests the filter refuses to run without month event titles; the version label needs none. */
class MemoryLeakFiltersTest {

    private final MonthEventRepository events = mock(MonthEventRepository.class);

    @Test
    void without_a_title_the_shipped_default_refuses_and_names_the_pool() {
        when(events.findRevealedTitles()).thenReturn(List.of());

        assertThatThrownBy(() -> new MemoryLeakFilters(events, true).current()).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.POOL_NOT_LOADED));
    }

    @Test
    void with_a_title_the_filter_catches_it_and_the_guard_can_be_switched_off_for_the_tests() {
        when(events.findRevealedTitles()).thenReturn(List.of("Imaginary Banking Panic Of Nowhere"));

        MemoryLeakFilter withTitle = new MemoryLeakFilters(events, true).current();

        assertThat(withTitle.scanText("the imaginary banking panic of nowhere again", "/x")).isNotEmpty();
        when(events.findRevealedTitles()).thenReturn(List.of());
        assertThat(new MemoryLeakFilters(events, false).current().terms().titles()).isEmpty();
    }

    @Test
    void the_version_label_comes_from_the_terms_file_alone() {
        when(events.findRevealedTitles()).thenReturn(List.of("Imaginary Banking Panic Of Nowhere"));
        MemoryLeakFilters filters = new MemoryLeakFilters(events, true);

        assertThat(filters.version()).startsWith("mf1-").isEqualTo(filters.current().terms().version());
    }
}
