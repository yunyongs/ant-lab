package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.history.EventCategory;
import antlab.history.EventPhase;
import org.junit.jupiter.api.Test;

class ReviewFeedItemDtoTest {

    private static RevealedFeedRow row(FeedKind kind, String title) {
        return new RevealedFeedRow(1, 1, kind, null, null, "문장", EventCategory.TRADE, "TYPE", EventPhase.START, title, null,
                "source", 2L);
    }

    @Test
    void 사건_종류가_아니면_사건_태그가_있어도_event는_null이고_signal은_false다() {
        ReviewFeedItemDto item = ReviewFeedItemDto.from(row(FeedKind.RISE, "제목"));
        assertThat(item.signal()).isFalse();
        assertThat(item.event()).isNull();
        assertThat(item.ordersFromItem()).isEqualTo(2);
    }

    @Test
    void 사건_종류는_signal이고_event를_채운다() {
        ReviewFeedItemDto item = ReviewFeedItemDto.from(row(FeedKind.EVENT, "제목"));
        assertThat(item.signal()).isTrue();
        assertThat(item.event().title()).isEqualTo("제목");
    }

    @Test
    void 사건_종류인데_사건_태그가_없으면_원천_모순으로_던진다() {
        assertThatThrownBy(() -> ReviewFeedItemDto.from(row(FeedKind.EVENT, null))).isInstanceOf(IllegalStateException.class);
    }
}
