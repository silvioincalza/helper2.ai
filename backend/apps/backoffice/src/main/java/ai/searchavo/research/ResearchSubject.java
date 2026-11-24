package ai.searchavo.research;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
