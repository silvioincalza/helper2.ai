package ai.helper2.catalog.internal;

import static ai.helper2.catalog.internal.JdbcCatalogWriter.ORIGIN_RANK;

import ai.helper2.catalog.DataOrigin;
import ai.helper2.shared.Pricing;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.WebSources;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class SqlReconciliation implements Reconciliation {

  private static final String[] TYPES_BY_SPECIFICITY =
      Arrays.stream(ResourceType.values())
          .sorted(Comparator.comparingInt(ResourceType::getSpecificity))
          .map(Enum::name)
          .toArray(String[]::new);

  private static final String[] PRICINGS =
      Arrays.stream(Pricing.values()).map(Enum::name).toArray(String[]::new);

  private final JdbcClient jdbc;

  SqlReconciliation(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void reconcile(Collection<UUID> resources, DataOrigin writtenBy) {
    reconcileFacts(resources, writtenBy);
    refreshEventDates(resources);
  }

  private void reconcileFacts(Collection<UUID> resources, DataOrigin origin) {
    if (resources.isEmpty() || origin.compareTo(DataOrigin.INDEXED) > 0) {
      return;
    }
    jdbc.sql(
            """
            WITH stated AS (
              SELECT sr.resource_id AS id,
                     count(DISTINCT lower(sr.license)) FILTER (WHERE %3$s >= %4$s) AS licences,
                     min(sr.license) FILTER (WHERE %3$s >= %4$s) AS licence,
                     count(DISTINCT pricing.stated) FILTER (WHERE %3$s >= %4$s) AS pricings,
                     min(pricing.stated) FILTER (WHERE %3$s >= %4$s) AS pricing,
                     count(DISTINCT open_source.stated) FILTER (WHERE %3$s >= %4$s) AS open_source_answers,
                     bool_and(open_source.stated) FILTER (WHERE %3$s >= %4$s) AS open_source,
                     (array_agg(sr.type ORDER BY array_position(CAST(:types AS text[]), sr.type))
                        FILTER (WHERE %3$s >= %4$s))[1] AS type
              FROM source_record sr JOIN resource owner ON owner.id = sr.resource_id
              CROSS JOIN LATERAL (SELECT CASE WHEN sr.extra ->> 'pricing' = ANY(CAST(:pricings AS text[]))
                                              THEN sr.extra ->> 'pricing' END AS stated) pricing
              CROSS JOIN LATERAL (SELECT CASE sr.extra ->> 'openSource' WHEN 'true' THEN true
                                                                        WHEN 'false' THEN false END AS stated) open_source
              WHERE sr.resource_id = ANY(:ids)
              GROUP BY sr.resource_id
            ), wanted AS (
              SELECT r.id,
                     CASE WHEN 'license' = ANY(r.curated_fields) THEN r.license
                          WHEN s.licences = 1 AND lower(r.license) = lower(s.licence) THEN r.license
                          WHEN s.licences = 1 THEN s.licence
                     END AS license,
                     CASE WHEN 'pricing' = ANY(r.curated_fields) THEN r.pricing
                          WHEN s.pricings = 1 THEN s.pricing
                     END AS pricing,
                     CASE WHEN 'openSource' = ANY(r.curated_fields) THEN r.open_source
                          WHEN s.open_source_answers = 1 THEN s.open_source
                     END AS open_source,
                     CASE WHEN 'type' = ANY(r.curated_fields) THEN r.type ELSE coalesce(s.type, r.type) END AS type
              FROM resource r JOIN stated s ON s.id = r.id
              WHERE r.data_origin IN ('INDEXED', 'OBSERVED')
                AND %1$s <= %2$s
            )
            UPDATE resource r SET license = w.license, type = w.type, pricing = w.pricing, open_source = w.open_source
            FROM wanted w
            WHERE r.id = w.id
              AND (r.license IS DISTINCT FROM w.license OR r.type IS DISTINCT FROM w.type
                   OR r.pricing IS DISTINCT FROM w.pricing OR r.open_source IS DISTINCT FROM w.open_source)
            """
                .formatted(
                    ORIGIN_RANK.formatted("r.data_origin"),
                    ORIGIN_RANK.formatted(":origin"),
                    ORIGIN_RANK.formatted("coalesce(sr.extra ->> 'dataOrigin', owner.data_origin)"),
                    ORIGIN_RANK.formatted("owner.data_origin")))
        .param("ids", resources.toArray(UUID[]::new))
        .param("types", TYPES_BY_SPECIFICITY)
        .param("pricings", PRICINGS)
        .param("origin", origin.name())
        .update();
  }

  private void refreshEventDates(Collection<UUID> resourceIds) {
    if (resourceIds.isEmpty()) {
      return;
    }
    jdbc.sql(
            """
            UPDATE resource r SET event_starts_at = s.starts, event_ends_at = s.ends
            FROM (SELECT resource_id,
                         CASE WHEN count(stated_start) > 0 THEN min(stated_start)
                              ELSE min(source_event_starts_at) END AS starts,
                         CASE WHEN count(stated_start) > 0 THEN max(stated_end)
                              ELSE max(source_event_ends_at) END AS ends
                  FROM (SELECT resource_id, source_event_starts_at, source_event_ends_at,
                               CASE WHEN source_id <> :aiRead THEN source_event_starts_at END AS stated_start,
                               CASE WHEN source_id <> :aiRead THEN source_event_ends_at END AS stated_end
                        FROM source_record WHERE resource_id = ANY(:ids)) rec
                  GROUP BY resource_id) s
            WHERE r.id = s.resource_id AND r.type = 'EVENT'
              AND (r.event_starts_at IS DISTINCT FROM s.starts OR r.event_ends_at IS DISTINCT FROM s.ends)
            """)
        .param("ids", resourceIds.toArray(UUID[]::new))
        .param("aiRead", WebSources.EVENTS_SCOUT)
        .update();
  }
}
