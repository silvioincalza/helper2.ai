package ai.helper2.catalog.enrichment;

import java.util.Collection;

/**
 * Inbound port of the catalog for the Scout: the last outcome of each source's run, which the
 * Sources page shows. The catalog owns the source table; the Scout only says how a run ended.
 */
public interface SourceOutcomes {

  /**
   * Records how the last run of [sourceId] ended (SUCCEEDED, FAILED or SKIPPED), checked now. A run
   * in progress records nothing.
   */
  void record(String sourceId, String outcome);

  /** Records FAILED, checked now, for each source in [sourceIds]: their runs were interrupted. */
  void markFailed(Collection<String> sourceIds);
}
