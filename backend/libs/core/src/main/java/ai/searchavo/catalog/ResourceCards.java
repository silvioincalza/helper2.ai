package ai.searchavo.catalog;

import ai.searchavo.search.DescribedSource;
import ai.searchavo.search.MergedResult;
import ai.searchavo.search.ResultMerger;
import ai.searchavo.search.ServedFrom;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.search.SourceContext;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.ResourceId;
import ai.searchavo.shared.SourceMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class ResourceCards {

  private final Resources store;
  private final List<DescribedSource> sources;
  private final ShadowLibraries shadowLibraries;
  private final ResultMerger merger = new ResultMerger();

  public ResourceCards(
      Resources store, List<DescribedSource> sources, ShadowLibraries shadowLibraries) {
    this.store = store;
    this.sources = sources;
    this.shadowLibraries = shadowLibraries;
  }

  public List<MergedResult> cardsOf(List<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    Map<UUID, ResourceRow> rows =
        store.findResources(ids).stream()
            .collect(Collectors.toMap(ResourceRow::id, r -> r, (a, b) -> a));
    Map<UUID, List<RecordRow>> records = store.recordsOf(rows.keySet());
    List<MergedResult> out = new ArrayList<>();
    for (UUID id : ids) {
      ResourceRow row = rows.get(id);
      if (row != null) {
        shownCard(row, records.getOrDefault(id, List.of())).ifPresent(out::add);
      }
    }
    return out;
  }

  public Optional<MergedResult> shownCard(ResourceRow row, List<RecordRow> records) {
    return card(row, records).filter(card -> !isOnAShadowLibrary(card));
  }

  private boolean isOnAShadowLibrary(MergedResult card) {
    return shadowLibraries.isShadowLibrary(card.getCanonicalUrl().getHost())
        || shadowLibraries.isShadowLibrary(card.getUrl().getHost());
  }

  public Optional<MergedResult> card(ResourceRow row, List<RecordRow> records) {
    ResourceId id = new ResourceId(row.id());
    return merged(id, records)
        .map(card -> {
          CanonicalUrl canonical =
              Objects.requireNonNullElse(
                  CanonicalUrl.of(row.canonicalUrl()), card.getCanonicalUrl());
          return withTheFactsOfTheRecordsTheRowCounts(card, row, records)
              .withIdentity(id, canonical)
              .withDiscovered(DateFact.observed(row.firstDiscoveredAt()))
              .withCategories(row.categories())
              .withCurated(
                  row.curatedFields(),
                  row.type(),
                  row.audience(),
                  row.techLevel(),
                  row.license(),
                  row.pricing(),
                  row.openSource());
        });
  }

  private MergedResult withTheFactsOfTheRecordsTheRowCounts(
      MergedResult card, ResourceRow row, List<RecordRow> records) {
    List<RecordRow> counted = recordsTheRowCounts(row, records);
    if (counted.size() == records.size()) {
      return card;
    }
    return merged(new ResourceId(row.id()), counted)
        .map(card::withReconciledFactsOf)
        .orElseGet(() -> card.withReconciledFacts(row.type(), null, null, null, List.of()));
  }

  private static List<RecordRow> recordsTheRowCounts(ResourceRow row, List<RecordRow> records) {
    DataOrigin origin = row.dataOrigin();
    if (!origin.rebuildsItsFactsFromItsRecords()) {
      return records;
    }
    return records.stream().filter(r -> r.isOfOriginOrStronger(origin)).toList();
  }

  private Optional<MergedResult> merged(ResourceId id, List<RecordRow> records) {
    if (records.isEmpty()) {
      return Optional.empty();
    }
    Map<String, SourceDescriptor> descriptors =
        sources.stream()
            .map(DescribedSource::getDescriptor)
            .collect(Collectors.toMap(SourceDescriptor::getId, d -> d, (a, b) -> a));

    Map<String, SourceContext> contexts = new HashMap<>();
    for (RecordRow r : records) {
      SourceDescriptor d = descriptors.get(r.sourceId());
      if (d != null) {
        SourceMode mode =
            d.getMode() == SourceMode.CURATED || d.getMode() == SourceMode.DEMO
                ? d.getMode()
                : SourceMode.INDEXED;
        contexts.put(r.sourceId(), new SourceContext(d.withMode(mode), ServedFrom.LIVE));
      }
    }
    List<MergedResult> merged =
        merger.merge(records.stream().map(RecordRow::hit).toList(), contexts);
    return merged.stream()
        .filter(m -> m.getId().equals(id))
        .findFirst()
        .or(() -> merged.stream().max(Comparator.comparingInt(m -> m.getHits().size())));
  }
}
