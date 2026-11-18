package ai.helper2.sources.events;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface UndatedEvents {

  record Position(Instant discoveredAt, UUID id) {

    private static final String SEPARATOR = "/";

    public String token() {
      return discoveredAt + SEPARATOR + id;
    }

    public static Optional<Position> parse(String token) {
      int at = token.lastIndexOf(SEPARATOR);
      if (at < 0) {
        return Optional.empty();
      }
      try {
        return Optional.of(
            new Position(
                Instant.parse(token.substring(0, at)), UUID.fromString(token.substring(at + 1))));
      } catch (DateTimeParseException | IllegalArgumentException e) {
        return Optional.empty();
      }
    }
  }

  record Page(String url, String title, Position position) {}

  UndatedEvents NONE = (since, after, limit) -> List.of();

  List<Page> discoveredSince(Instant since, @Nullable Position after, int limit);
}
