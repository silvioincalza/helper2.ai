package ai.helper2.research;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the research reads about a review item. {@code confirmed} holds the candidate's own
 * confirmed values (license, pricing, description and the others under {@code confirmed} in its
 * details), so a research result records what it saw and a later edit makes it stale.
 */
public record ResearchSubject(
    UUID itemId,
    String kind,
    String url,
    String title,
    String type,
    List<String> tags,
    @Nullable String snippet,
    Map<String, String> confirmed) {

  public ResearchSubject {
    confirmed = confirmed == null ? Map.of() : Map.copyOf(confirmed);
  }

  public ResearchSubject(
      UUID itemId,
      String kind,
      String url,
      String title,
      String type,
      List<String> tags,
      @Nullable String snippet) {
    this(itemId, kind, url, title, type, tags, snippet, Map.of());
  }
}
