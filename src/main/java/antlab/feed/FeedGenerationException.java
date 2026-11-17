package antlab.feed;

import java.util.Objects;

/**
 * A feed that cannot be generated is a data-preparation defect, not a client error. The message carries the failure code and a fixed
 * reason only: never a value, a month, a date or a name.
 */
public final class FeedGenerationException extends IllegalStateException {

    private final FeedFailure failure;

    public FeedGenerationException(FeedFailure failure, String reason) {
        super(Objects.requireNonNull(failure, "failure").name() + ": " + reason);
        this.failure = failure;
    }

    public FeedFailure failure() {
        return failure;
    }
}
