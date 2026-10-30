package ai.helper2.sources.support;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Lets a collector run less often than the Scout schedule. The Scout's checkpoint is the start of
 * the last complete pass, so "due" means: no complete pass yet, or the interval has passed since it.
 */
public record RunInterval(Duration every) {

    public RunInterval {
        if (every == null || every.isNegative()) {
            throw new IllegalArgumentException("run interval must be zero or positive");
        }
    }

    public boolean allowsPass(String checkpoint, String resumeCursor, Instant now) {
        return resumeCursor != null || isDue(checkpoint, now);
    }

    public boolean isDue(String checkpoint, Instant now) {
        if (checkpoint == null || every.isZero()) {
            return true;
        }
        try {
            return !now.isBefore(Instant.parse(checkpoint).plus(every));
        } catch (DateTimeParseException e) {
            // An unreadable checkpoint must not block collection forever.
            return true;
        }
    }
}
