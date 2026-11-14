package ai.helper2.catalog.enrichment;

import java.util.Collection;

public interface SourceOutcomes {
  void record(String sourceId, String outcome);

  void markFailed(Collection<String> sourceIds);
}
