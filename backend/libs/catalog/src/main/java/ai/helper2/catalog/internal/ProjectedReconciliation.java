package ai.helper2.catalog.internal;

import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.RecordRow;
import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.ResourceRow;
import ai.helper2.catalog.Resources;
import ai.helper2.search.MergedResult;
import ai.helper2.shared.Pricing;
import ai.helper2.shared.ResourceType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

class ProjectedReconciliation implements Reconciliation {

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final ResourceRows rows;
  private final Resources resources;
  private final ResourceCards cards;

  ProjectedReconciliation(
      JdbcClient jdbc,
      ObjectMapper json,
      ResourceRows rows,
      Resources resources,
      ResourceCards cards) {
    this.jdbc = jdbc;
    this.json = json;
    this.rows = rows;
    this.resources = resources;
    this.cards = cards;
  }

  record ReconciledFacts(
      UUID id,
      ResourceType type,
      @Nullable String license,
      @Nullable Pricing pricing,
      @Nullable Boolean openSource,
      @Nullable Instant eventStartsAt,
      @Nullable Instant eventEndsAt) {

    static ReconciledFacts storedIn(ResourceRow row) {
      return new ReconciledFacts(
          row.id(),
          row.type(),
          row.license(),
          row.pricing(),
          row.openSource(),
          row.eventStartsAt(),
          row.eventEndsAt());
    }

    ReconciledFacts withFactsOf(MergedResult card) {
      return new ReconciledFacts(
          id,
          card.getType(),
          card.getLicense(),
          card.getPricing(),
          card.getOpenSource(),
          eventStartsAt,
          eventEndsAt);
    }

    ReconciledFacts withEventDaysOf(MergedResult card) {
      if (type != ResourceType.EVENT) {
        return this;
      }
      return new ReconciledFacts(
          id,
          type,
          license,
          pricing,
          openSource,
          card.getEventStart().getValue(),
          card.getEventEnd().getValue());
    }
  }

  @Override
  public void reconcile(Collection<UUID> touched, DataOrigin writtenBy) {
    if (touched.isEmpty()) {
      return;
    }
    List<ResourceRow> locked = lockedRowsOf(touched);
    Map<UUID, List<RecordRow>> records = resources.recordsOf(touched);
    List<ReconciledFacts> changed =
        locked.stream()
            .map(row -> projected(row, records.getOrDefault(row.id(), List.of()), writtenBy))
            .flatMap(Optional::stream)
            .toList();
    write(changed);
  }

  private List<ResourceRow> lockedRowsOf(Collection<UUID> touched) {
    return jdbc.sql("SELECT * FROM resource WHERE id = ANY(:ids) ORDER BY id FOR UPDATE")
        .param("ids", touched.stream().distinct().toArray(UUID[]::new))
        .query(rows::mapResource)
        .list();
  }

  private Optional<ReconciledFacts> projected(
      ResourceRow row, List<RecordRow> records, DataOrigin writtenBy) {
    ReconciledFacts stored = ReconciledFacts.storedIn(row);
    return cards
        .card(row, records)
        .map(card -> {
          ReconciledFacts facts =
              rebuildsTheFactsOf(row, writtenBy) ? stored.withFactsOf(card) : stored;
          return facts.withEventDaysOf(card);
        })
        .filter(facts -> !facts.equals(stored));
  }

  private static boolean rebuildsTheFactsOf(ResourceRow row, DataOrigin writtenBy) {
    return writtenBy.rebuildsItsFactsFromItsRecords()
        && row.dataOrigin().rebuildsItsFactsFromItsRecords()
        && row.dataOrigin().compareTo(writtenBy) <= 0;
  }

  private void write(List<ReconciledFacts> changed) {
    if (changed.isEmpty()) {
      return;
    }
    ArrayNode values = json.createArrayNode();
    for (ReconciledFacts facts : changed) {
      values
          .addObject()
          .put("id", facts.id().toString())
          .put("type", facts.type().name())
          .put("license", facts.license())
          .put("pricing", facts.pricing() == null ? null : facts.pricing().name())
          .put("open_source", facts.openSource())
          .put("event_starts_at", Objects.toString(facts.eventStartsAt(), null))
          .put("event_ends_at", Objects.toString(facts.eventEndsAt(), null));
    }
    jdbc.sql(
            """
            UPDATE resource r SET type = p.type, license = p.license, pricing = p.pricing,
                open_source = p.open_source, event_starts_at = p.event_starts_at, event_ends_at = p.event_ends_at
            FROM jsonb_to_recordset(CAST(:values AS jsonb)) AS p(
                id uuid, type text, license text, pricing text, open_source boolean,
                event_starts_at timestamptz, event_ends_at timestamptz)
            WHERE r.id = p.id
            """)
        .param("values", json.writeValueAsString(values))
        .update();
  }
}
