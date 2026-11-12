package antlab.season;

import antlab.history.EventCategory;
import antlab.history.EventPhase;

/** One event tag of a turn's real month, as read by the review event query of {@code SeasonPathRepository}. Carries no event or block id. */
public record RevealedEventRow(int turnNo, EventCategory category, String eventType, EventPhase phase, String title,
        String hindsight, String source) {
}
