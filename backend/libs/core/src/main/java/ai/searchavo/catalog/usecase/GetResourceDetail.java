package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.AwesomeListEntries;
import ai.searchavo.catalog.DirectoryRating;
import ai.searchavo.catalog.EnrichmentFacts;
import ai.searchavo.catalog.RecordRow;
import ai.searchavo.catalog.ResourceCards;
import ai.searchavo.catalog.ResourceDetail;
import ai.searchavo.catalog.ResourceRatings;
import ai.searchavo.catalog.ResourceRow;
import ai.searchavo.catalog.Resources;
import ai.searchavo.search.FieldConflict;
import ai.searchavo.search.MergedResult;
import ai.searchavo.search.SourceHit;
import ai.searchavo.shared.ValueOrigin;
import ai.searchavo.shared.Verification;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public class GetResourceDetail {

  private final Resources store;
  private final EnrichmentFacts enrichment;
  private final ResourceCards cards;
  private final AwesomeListEntries listEntries;
  private final ResourceRatings ratings;

  public GetResourceDetail(
      Resources store,
      EnrichmentFacts enrichment,
      ResourceCards cards,
      AwesomeListEntries listEntries,
      ResourceRatings ratings) {
    this.ratings = ratings;
    this.store = store;
    this.enrichment = enrichment;
    this.cards = cards;
    this.listEntries = listEntries;
  }

  public Optional<ResourceDetail> find(UUID id) {
    List<RecordRow> records = store.recordsOf(id);
    if (records.isEmpty()) {
      return store
          .ownerOfRecordUrl(id)
          .filter(owner -> !owner.equals(id))
          .flatMap(owner -> detail(owner, store.recordsOf(owner)));
    }
    return detail(id, records);
  }

  private Optional<ResourceDetail> detail(UUID id, List<RecordRow> records) {
    Optional<ResourceRow> row = store.findResource(id);
    if (row.isEmpty()) {
      return Optional.empty();
    }
    return cards.shownCard(row.get(), records).map(card -> detailOf(id, row.get(), card, records));
  }

  private ResourceDetail detailOf(
      UUID id, ResourceRow row, MergedResult card, List<RecordRow> records) {
    return new ResourceDetail(
        card,
        assertions(id, records, card.getConflicts()),
        enrichment.relationships(id),
        enrichment.summary(id),
        records.stream().map(ResourceDetail.SourceRecordInfo::of).toList(),
        row.enrichmentStatus(),
        listEntries.naming(id, ResourceDetail.FoundIn.MAX_LISTS),
        // UC-007 BR-011: no stated figure, no rating: the page says "not rated yet".
        DirectoryRating.of(ratings.stated(id)).orElse(null));
  }

  private List<ResourceDetail.Assertion> assertions(
      UUID id, List<RecordRow> records, List<FieldConflict> conflicts) {
    List<ResourceDetail.Assertion> assertions =
        new ArrayList<>(sourceAssertions(records, conflicts));
    assertions.addAll(enrichment.assertions(id));
    return assertions;
  }

  private List<ResourceDetail.Assertion> sourceAssertions(
      List<RecordRow> records, List<FieldConflict> conflicts) {
    Set<String> conflicting =
        conflicts.stream().map(FieldConflict::getField).collect(Collectors.toSet());
    List<ResourceDetail.Assertion> out = new ArrayList<>();
    for (RecordRow r : records) {
      out.addAll(assertionsOf(r, conflicting));
    }
    return out;
  }

  private static List<ResourceDetail.Assertion> assertionsOf(RecordRow r, Set<String> conflicting) {
    ResourceDetail.Evidence evidence =
        new ResourceDetail.Evidence(r.id(), URI.create(r.sourceUrl()), r.retrievedAt(), null);
    ValueOrigin origin = originOf(r.hit());
    List<ResourceDetail.Assertion> out = new ArrayList<>();
    statedFields(r.hit())
        .forEach((field, value) -> {
          if (value != null) {
            Verification v =
                conflicting.contains(field) ? Verification.CONFLICTING : Verification.VERIFIED;
            out.add(
                new ResourceDetail.Assertion(
                    field, value, origin, v, r.sourceId(), List.of(evidence)));
          }
        });
    return out;
  }

  private static ValueOrigin originOf(SourceHit h) {
    return h.getDescriptionOrigin() == ValueOrigin.CURATED
        ? ValueOrigin.CURATED
        : ValueOrigin.SOURCE_PROVIDED;
  }

  private static Map<String, String> statedFields(SourceHit h) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("title", h.getTitle());
    fields.put("description", h.getDescription());
    fields.put("license", h.getLicense());
    fields.put("published", textOf(h.getPublished().getValue()));
    fields.put("updated", textOf(h.getUpdated().getValue()));
    return fields;
  }

  private static @Nullable String textOf(@Nullable Object value) {
    return value == null ? null : value.toString();
  }
}
