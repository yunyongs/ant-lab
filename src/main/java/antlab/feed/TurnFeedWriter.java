package antlab.feed;

import antlab.season.Season;
import antlab.season.TurnFeed;
import antlab.season.TurnFeedRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores the generated rows of one turn as {@code turn_feed}, in the caller's transaction (no new one is opened). */
@Component
public class TurnFeedWriter {

    private final TurnFeedRepository feeds;

    public TurnFeedWriter(TurnFeedRepository feeds) {
        this.feeds = feeds;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<TurnFeed> write(Season season, int turnNo, List<GeneratedFeedRow> rows) {
        List<TurnFeed> saved = new ArrayList<>(rows.size());
        for (GeneratedFeedRow row : rows) {
            saved.add(feeds.save(new TurnFeed(season, turnNo, row.seq(), row.kind(), row.assetId(), row.assetGroupId(),
                    row.monthEventId(), row.categoryHidden(), row.direction(), row.strength(), row.repeatCount(), row.templateId(),
                    row.templateVarsJson(), row.body())));
        }
        return saved;
    }
}
