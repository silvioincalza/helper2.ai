package ai.helper2.catalog;

import ai.helper2.search.DescribedSource;
import ai.helper2.search.MergedResult;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.SourceContext;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceId;
import ai.helper2.shared.SourceMode;
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
  private final ResultMerger merger = new ResultMerger();

  public ResourceCards(Resources store, List<DescribedSource> sources) {
    this.store = store;
    this.sources = sources;
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
        card(row, records.getOrDefault(id, List.of())).ifPresent(out::add);
      }
    }
    return out;
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
