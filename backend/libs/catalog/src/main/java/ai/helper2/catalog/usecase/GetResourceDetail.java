package ai.helper2.catalog.usecase;

import ai.helper2.catalog.EnrichmentFacts;
import ai.helper2.catalog.RecordRow;
import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.ResourceRow;
import ai.helper2.catalog.Resources;
import ai.helper2.search.FieldConflict;
import ai.helper2.search.MergedResult;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
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

  public GetResourceDetail(Resources store, EnrichmentFacts enrichment, ResourceCards cards) {
    this.store = store;
    this.enrichment = enrichment;
    this.cards = cards;
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
    return cards.card(row.get(), records).map(card -> detailOf(id, row.get(), card, records));
  }

  private ResourceDetail detailOf(
      UUID id, ResourceRow row, MergedResult card, List<RecordRow> records) {
    return new ResourceDetail(
        card,
        assertions(id, records, card.getConflicts()),
        enrichment.relationships(id),
        enrichment.summary(id),
        records.stream().map(GetResourceDetail::sourceRecordInfo).toList(),
        row.enrichmentStatus());
  }

  private List<ResourceDetail.Assertion> assertions(
      UUID id, List<RecordRow> records, List<FieldConflict> conflicts) {
    List<ResourceDetail.Assertion> assertions =
        new ArrayList<>(sourceAssertions(records, conflicts));
    assertions.addAll(enrichment.assertions(id));
    return assertions;
  }

  private static ResourceDetail.SourceRecordInfo sourceRecordInfo(RecordRow r) {
    return new ResourceDetail.SourceRecordInfo(
        r.sourceId(), r.externalId(), URI.create(r.sourceUrl()), r.retrievedAt(), r.fingerprint());
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
