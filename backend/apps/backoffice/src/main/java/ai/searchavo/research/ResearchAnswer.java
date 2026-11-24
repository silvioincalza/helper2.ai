package ai.searchavo.research;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record ResearchAnswer(
    Verdict verdict, String reason, @Nullable String duplicateOf, List<Claim> attributes) {

  public enum Verdict {
    KEEP,
    REJECT,
    DUPLICATE
  }

  public record Claim(String attribute, String value, List<Cite> citations) {}

  public record Cite(String evidenceId, String excerpt) {}
}
