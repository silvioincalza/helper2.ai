package ai.helper2.catalog.internal;

import ai.helper2.catalog.internal.ResourceStore.RecordRow;
import ai.helper2.catalog.internal.ResourceStore.ResourceRow;
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
import org.springframework.stereotype.Component;

/**
 * Stored resources as the same cards search shows; shared by the detail page and the home page
 * lists.
 */
@Component
public class ResourceCards {

  private final ResourceStore store;
  private final List<DescribedSource> sources;
  private final ResultMerger merger = new ResultMerger();

  public ResourceCards(ResourceStore store, List<DescribedSource> sources) {
    this.store = store;
    this.sources = sources;
  }

  /**
   * The cards of [ids] in the same order, in a fixed number of statements (the rows with their
   * facts, then the records of all of them). An id whose resource is gone or whose records no
   * longer merge has no card.
   */
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

  /**
   * One resource as a card: its source records merged, plus the date Helper2 first discovered it.
   * The card always carries the resource's own id: records may merge to another identity (a
   * repository URL), and a card with another id would link to a page that does not exist.
   */
  public Optional<MergedResult> card(ResourceRow row, List<RecordRow> records) {
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
        // Detail data comes from Helper2's store: INDEXED, unless curated or demo fixtures.
        SourceMode mode =
            d.getMode() == SourceMode.CURATED || d.getMode() == SourceMode.DEMO
                ? d.getMode()
                : SourceMode.INDEXED;
        contexts.put(r.sourceId(), new SourceContext(d.withMode(mode), ServedFrom.LIVE));
      }
    }
    List<MergedResult> merged =
        merger.merge(records.stream().map(RecordRow::hit).toList(), contexts);
    ResourceId id = new ResourceId(row.id());
    MergedResult card =
        merged.stream()
            .filter(m -> m.getId().equals(id))
            .findFirst()
            .orElseGet(() ->
                merged.stream()
                    .max(Comparator.comparingInt(m -> m.getHits().size()))
                    .orElseThrow());
    CanonicalUrl canonical =
        Objects.requireNonNullElse(CanonicalUrl.of(row.canonicalUrl()), card.getCanonicalUrl());
    return Optional.of(
        card.withIdentity(id, canonical)
            .withDiscovered(DateFact.observed(row.firstDiscoveredAt()))
            // The categories the catalog assigned (ADR 0013), the ones search filters by.
            .withCategories(row.categories())
            // An admin's value wins over every record (ADR 0026).
            .withCurated(
                row.curatedFields(),
                row.type(),
                row.audience(),
                row.techLevel(),
                row.license(),
                row.pricing(),
                row.openSource()));
  }
}
