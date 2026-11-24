package ai.searchavo.catalog.internal;

import ai.searchavo.catalog.CatalogWriter;
import ai.searchavo.catalog.DataOrigin;
import ai.searchavo.catalog.RecordRow;
import ai.searchavo.catalog.Resources;
import ai.searchavo.search.DescribedSource;
import ai.searchavo.search.DiscoveredResources;
import ai.searchavo.search.MergedResult;
import ai.searchavo.search.RecordedOutcomes;
import ai.searchavo.search.ResultObserver;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceHealth;
import ai.searchavo.search.SourceHit;
import ai.searchavo.search.SourceOutcome;
import ai.searchavo.shared.ResourceId;
import ai.searchavo.shared.SourceMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CatalogResultObserver
    implements ResultObserver, RecordedOutcomes, DiscoveredResources {

  private final CatalogWriter writer;
  private final Resources store;
  private final JdbcSourceOutcomes outcomes;
  private final List<DescribedSource> sources;
  private final ObservationQueue observations;
  private final Set<String> liveSources;
  private final Set<String> demoSources;

  CatalogResultObserver(
      CatalogWriter writer,
      Resources store,
      JdbcSourceOutcomes outcomes,
      List<DescribedSource> sources,
      ObservationQueue observations) {
    this.writer = writer;
    this.store = store;
    this.outcomes = outcomes;
    this.sources = sources;
    this.observations = observations;
    this.liveSources = idsWithMode(SourceMode.LIVE);
    this.demoSources = idsWithMode(SourceMode.DEMO);
  }

  @Override
  public Map<String, SourceHealth.Entry> lastOutcomes() {
    return outcomes.recordedOutcomes().stream()
        .collect(
            Collectors.toMap(
                JdbcSourceOutcomes.RecordedOutcome::sourceId,
                r -> new SourceHealth.Entry(SourceOutcome.valueOf(r.outcome()), r.checkedAt())));
  }

  @Override
  public Map<String, Long> countsSince(Instant since) {
    Map<String, Long> counts = store.discoveredSince(since);
    counts.put(
        CatalogConfiguration.INDEX_SOURCE,
        store.discoveredInIndexSince(since, Set.of(DataOrigin.INDEXED, DataOrigin.OBSERVED)));
    counts.put(CatalogConfiguration.SEMANTIC_SOURCE, null);
    return counts;
  }

  @Override
  public Map<ResourceId, Instant> firstDiscovered(List<MergedResult> results) {
    Map<UUID, Instant> found =
        store.firstDiscovered(results.stream().map(r -> r.getId().getValue()).toList());
    Map<ResourceId, Instant> out = new HashMap<>();
    found.forEach((k, v) -> out.put(new ResourceId(k), v));
    return out;
  }

  @Override
  public void record(List<MergedResult> results) {
    if (!results.isEmpty()) {
      observations.offer(() -> persistObserved(results));
    }
  }

  @Override
  public List<SourceHit> recordedHits(String sourceId, List<ResourceId> ids) {
    Map<UUID, List<RecordRow>> recordsByOwner = new HashMap<>();
    List<SourceHit> hits = new ArrayList<>(ids.size());
    for (ResourceId id : ids) {
      store
          .ownerOfRecordUrl(id.getValue())
          .flatMap(owner ->
              recordsByOwner.computeIfAbsent(owner, store::recordsOf).stream()
                  .filter(r ->
                      r.sourceId().equals(sourceId)
                          && ResourceId.of(r.hit().getCanonicalUrl()).equals(id))
                  .findFirst())
          .ifPresent(r -> hits.add(r.hit()));
    }
    return hits;
  }

  private void persistObserved(List<MergedResult> results) {
    writer.upsert(results, DataOrigin.OBSERVED, liveSources);
    if (!demoSources.isEmpty()) {
      writer.upsert(results, DataOrigin.DEMO, demoSources);
    }
  }

  private Set<String> idsWithMode(SourceMode mode) {
    return sources.stream()
        .map(DescribedSource::getDescriptor)
        .filter(d -> d.getMode() == mode)
        .map(SourceDescriptor::getId)
        .collect(Collectors.toSet());
  }
}
