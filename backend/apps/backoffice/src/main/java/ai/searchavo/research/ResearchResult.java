package ai.searchavo.research;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record ResearchResult(
    ResearchAnswer.Verdict verdict,
    String reason,
    @Nullable String duplicateOf,
    List<Attribute> attributes,
    List<String> dropped) {

  public enum Status {
    CONFIRMED,
    SINGLE_SOURCE,
    CONFLICT,
    NOT_FOUND
  }

  public record Attribute(
      String name,
      @Nullable String current,
      List<String> proposed,
      Status status,
      List<Source> sources) {}

  public record Source(
      String evidenceId,
      String tool,
      String url,
      String excerpt,
      String value,
      boolean authoritative) {}
}
