package ai.searchavo.catalog.enrichment;

import java.time.Instant;
import java.util.List;

public interface ValidationIssues {

  record Count(String subject, String rule, int runs) {}

  record Summary(int runs, List<Count> top) {}

  Summary since(Instant since, int top);
}
