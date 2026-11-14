package ai.helper2.catalog.internal;

import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.RecordRow;
import ai.helper2.catalog.ResourceRow;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.SourceHit;
import ai.helper2.search.Vitality;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.CategoryMatcher;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.DatePrecision;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.Pricing;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.TechLevel;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.WebPageTitle;
import ai.helper2.shared.WebSources;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class ResourceRows {

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final CategoryMatcher categories;

  ResourceRows(JdbcClient jdbc, ObjectMapper json, CategoryMatcher categories) {
    this.jdbc = jdbc;
    this.json = json;
    this.categories = categories;
  }

  RecordRow mapRecord(ResultSet rs, int i) throws SQLException {
    JsonNode extra = json.readTree(rs.getString("extra"));
    return new RecordRow(
        rs.getObject("id", UUID.class),
        rs.getString("source_id"),
        rs.getString("external_id"),
        rs.getObject("resource_id", UUID.class),
        rs.getString("source_url"),
        rs.getTimestamp("retrieved_at").toInstant(),
        rs.getString("fingerprint"),
        strongestOriginThatWrote(extra),
        toHit(rs, extra));
  }

  private static DataOrigin strongestOriginThatWrote(JsonNode extra) {
    String recorded = extra.path("dataOrigin").asString(null);
    return recorded == null ? null : known(DataOrigin.class, recorded, DataOrigin.OBSERVED);
  }

  ResourceRow mapResource(ResultSet rs, int i) throws SQLException {
    return new ResourceRow(
        rs.getObject("id", UUID.class),
        rs.getString("canonical_url"),
        rs.getString("url"),
        rs.getString("title"),
        originIsKnown(rs.getString("description_origin")) ? rs.getString("description") : null,
        known(ValueOrigin.class, rs.getString("description_origin"), ValueOrigin.SOURCE_PROVIDED),
        known(ResourceType.class, rs.getString("type"), ResourceType.OTHER),
        known(Audience.class, rs.getString("audience"), Audience.UNKNOWN),
        enumOrNull(TechLevel.class, rs.getString("tech_level")),
        rs.getString("license"),
        enumOrNull(Pricing.class, rs.getString("pricing")),
        (Boolean) rs.getObject("open_source"),
        instant(rs.getTimestamp("published_at")),
        known(DatePrecision.class, rs.getString("published_precision"), DatePrecision.UNKNOWN),
        rs.getString("published_source_id"),
        instant(rs.getTimestamp("updated_at")),
        rs.getString("updated_source_id"),
        instant(rs.getTimestamp("event_starts_at")),
        instant(rs.getTimestamp("event_ends_at")),
        instant(rs.getTimestamp("first_discovered_at")),
        DataOrigin.valueOf(rs.getString("data_origin")),
        rs.getString("enrichment_status"),
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        List.of(),
        Set.of((String[]) rs.getArray("curated_fields").getArray()));
  }

  List<ResourceRow> withFacts(List<ResourceRow> rows) {
    if (rows.isEmpty()) {
      return rows;
    }
    UUID[] ids = rows.stream().map(ResourceRow::id).distinct().toArray(UUID[]::new);
    Map<UUID, Map<String, Set<String>>> labels = new HashMap<>();
    jdbc.sql(
            "SELECT resource_id, kind, value FROM resource_label WHERE resource_id = ANY(:ids) ORDER BY value")
        .param("ids", ids)
        .query((rs, i) ->
            labels
                .computeIfAbsent(rs.getObject(1, UUID.class), k -> new LinkedHashMap<>())
                .computeIfAbsent(rs.getString(2), k -> new LinkedHashSet<>())
                .add(rs.getString(3)))
        .list();
    labels
        .values()
        .forEach(l ->
            l.computeIfPresent(
                "CATEGORY", (k, v) -> new LinkedHashSet<>(categories.getCatalog().ordered(v))));
    Map<UUID, List<PopularityMetric>> popularity = popularity(ids);
    Set<UUID> pageTitled = pageTitled(ids);
    return rows.stream()
        .map(r ->
            r.withFacts(
                labels.getOrDefault(r.id(), Map.of()), popularity.getOrDefault(r.id(), List.of())))
        .map(r ->
            pageTitled.contains(r.id()) && r.dataOrigin() != DataOrigin.CURATED
                ? r.withPageTitle()
                : r)
        .toList();
  }

  Map<UUID, Vitality> statedVitality(UUID[] ids) {
    if (ids.length == 0) {
      return Map.of();
    }
    Map<UUID, Vitality> stated = new HashMap<>();
    jdbc.sql(
            """
            SELECT resource_id,
                   bool_or(CAST(extra -> 'vitality' ->> 'archived' AS boolean)) AS archived,
                   bool_or(CAST(extra -> 'vitality' ->> 'fork' AS boolean)) AS fork,
                   bool_or(CAST(extra -> 'vitality' ->> 'deprecated' AS boolean)) AS deprecated,
                   bool_and(CAST(extra -> 'vitality' ->> 'running' AS boolean)) AS running
            FROM source_record
            WHERE resource_id = ANY(:ids) AND extra -> 'vitality' IS NOT NULL
            GROUP BY resource_id
            """)
        .param("ids", ids)
        .query((rs, i) ->
            stated.put(
                rs.getObject("resource_id", UUID.class),
                new Vitality(
                    rs.getObject("archived", Boolean.class),
                    rs.getObject("fork", Boolean.class),
                    rs.getObject("deprecated", Boolean.class),
                    rs.getObject("running", Boolean.class))))
        .list();
    return stated;
  }

  Map<UUID, StatedEventFacts> statedEventFacts(UUID[] ids) {
    if (ids.length == 0) {
      return Map.of();
    }
    Map<UUID, List<RecordOfAnEvent>> records = new LinkedHashMap<>();
    jdbc.sql(
            """
            WITH record_days AS (
              SELECT resource_id, source_id, source_event_starts_at AS starts, source_event_ends_at AS ends,
                     extra ->> 'location' AS location, extra ->> 'eventKind' AS kind,
                     extra ->> 'eventFormat' AS format,
                     source_id = :aiRead AS read_by_ai,
                     bool_or(source_id <> :aiRead AND source_event_starts_at IS NOT NULL)
                       OVER (PARTITION BY resource_id) AS a_source_states_the_days
              FROM source_record
              WHERE resource_id = ANY(:ids)
            )
            SELECT resource_id, source_id, starts, ends, location, kind, format, a_source_states_the_days
            FROM record_days
            WHERE NOT (a_source_states_the_days AND read_by_ai)
            ORDER BY resource_id, source_id
            """)
        .param("ids", ids)
        .param("aiRead", WebSources.EVENTS_SCOUT)
        .query((rs, i) -> {
          String sourceId = rs.getString("source_id");
          DateFact starts = eventDay(rs.getTimestamp("starts"), sourceId);
          return records
              .computeIfAbsent(rs.getObject("resource_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new RecordOfAnEvent(
                      starts,
                      eventDay(rs.getTimestamp("ends"), sourceId),
                      rs.getString("location"),
                      enumOrNull(EventKind.class, rs.getString("kind")),
                      enumOrNull(EventFormat.class, rs.getString("format")),
                      !rs.getBoolean("a_source_states_the_days") || starts.getValue() != null));
        })
        .list();
    Map<UUID, StatedEventFacts> stated = new HashMap<>();
    records.forEach((id, ofTheEvent) -> stated.put(id, statedBy(ofTheEvent)));
    return stated;
  }

  private record RecordOfAnEvent(
      DateFact starts,
      DateFact ends,
      String location,
      EventKind kind,
      EventFormat format,
      boolean statesTheEvent) {}

  private static StatedEventFacts statedBy(List<RecordOfAnEvent> records) {
    List<RecordOfAnEvent> stating =
        records.stream().filter(RecordOfAnEvent::statesTheEvent).toList();
    return new StatedEventFacts(
        DateFact.earliest(records.stream().map(RecordOfAnEvent::starts).toList()),
        DateFact.latest(records.stream().map(RecordOfAnEvent::ends).toList()),
        firstStated(stating, RecordOfAnEvent::location),
        firstStated(stating, RecordOfAnEvent::kind),
        firstStated(stating, RecordOfAnEvent::format));
  }

  private static <T> T firstStated(
      List<RecordOfAnEvent> records, Function<RecordOfAnEvent, T> fact) {
    return records.stream().map(fact).filter(Objects::nonNull).findFirst().orElse(null);
  }

  Set<UUID> pageTitled(UUID[] ids) {
    return new HashSet<>(
        jdbc.sql(
                """
                SELECT resource_id FROM source_record WHERE resource_id = ANY(:ids)
                GROUP BY resource_id HAVING bool_and(source_id = ANY(:web) OR source_id LIKE :discovery)
                """)
            .param("ids", ids)
            .param("web", WebSources.FIXED.toArray(String[]::new))
            .param("discovery", WebSources.DISCOVERY_LIKE)
            .query(UUID.class)
            .list());
  }

  private Map<UUID, List<PopularityMetric>> popularity(UUID[] ids) {
    record Row(UUID resourceId, PopularityMetric metric) {}
    List<Row> rows =
        jdbc.sql(
                """
                SELECT sr.resource_id, sr.source_id, sr.retrieved_at, p->>'kind' AS kind, p->>'value' AS value, p->>'asOf' AS as_of
                FROM source_record sr, jsonb_array_elements(CASE WHEN jsonb_typeof(sr.extra -> 'popularity') = 'array'
                                                                 THEN sr.extra -> 'popularity' ELSE '[]'::jsonb END) p
                WHERE sr.resource_id = ANY(:ids)
                """)
            .param("ids", ids)
            .query((rs, i) -> {
              PopularityMetric m =
                  metric(
                      rs.getString("kind"),
                      rs.getString("value"),
                      rs.getString("source_id"),
                      rs.getString("as_of"),
                      rs.getTimestamp("retrieved_at").toInstant());
              return new Row(rs.getObject("resource_id", UUID.class), m);
            })
            .list();
    Map<UUID, Map<String, PopularityMetric>> newest = new HashMap<>();
    for (Row row : rows) {
      PopularityMetric m = row.metric();
      if (m == null) {
        continue;
      }
      newest
          .computeIfAbsent(row.resourceId(), k -> new TreeMap<>())
          .merge(
              m.getKind() + "|" + m.getSourceId(),
              m,
              (a, b) -> a.getAsOf().isAfter(b.getAsOf()) ? a : b);
    }
    Map<UUID, List<PopularityMetric>> out = new HashMap<>();
    newest.forEach((id, byKey) ->
        out.put(
            id,
            byKey.values().stream()
                .sorted(Comparator.comparing(PopularityMetric::getKind))
                .toList()));
    return out;
  }

  private static PopularityMetric metric(
      String kind, String value, String sourceId, String asOf, Instant retrievedAt) {
    try {
      Instant at = asOf == null ? retrievedAt : Instant.parse(asOf);
      return new PopularityMetric(
          PopularityKind.valueOf(kind), Long.parseLong(value), sourceId, at);
    } catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
      return null;
    }
  }

  private SourceHit toHit(ResultSet rs, JsonNode x) throws SQLException {
    String sourceId = rs.getString("source_id");
    Instant retrieved = rs.getTimestamp("retrieved_at").toInstant();
    Instant pub = instant(rs.getTimestamp("source_published_at"));
    Instant upd = instant(rs.getTimestamp("source_updated_at"));
    List<PopularityMetric> popularity = new ArrayList<>();
    x.path("popularity")
        .forEach(p -> {
          PopularityMetric read =
              metric(
                  p.path("kind").asString(null),
                  p.path("value").asString(null),
                  sourceId,
                  p.path("asOf").asString(null),
                  retrieved);
          if (read != null) {
            popularity.add(read);
          }
        });
    String origin = x.path("descriptionOrigin").asString("SOURCE_PROVIDED");
    CanonicalUrl canonical = CanonicalUrl.of(rs.getString("canonical_url"));
    return new SourceHit(
            sourceId,
            rs.getString("external_id"),
            URI.create(rs.getString("source_url")),
            canonical,
            CanonicalUrl.of(rs.getString("repository_url")),
            WebPageTitle.forSource(sourceId, rs.getString("title"), canonical),
            originIsKnown(origin) ? rs.getString("description") : null,
            known(ValueOrigin.class, origin, ValueOrigin.SOURCE_PROVIDED),
            known(ResourceType.class, rs.getString("type"), ResourceType.OTHER),
            known(Audience.class, x.path("audience").asString("UNKNOWN"), Audience.UNKNOWN),
            enumOrNull(TechLevel.class, x.path("techLevel").asString(null)),
            strings(x.path("useCases")),
            strings(x.path("tags")),
            rs.getString("license"),
            enumOrNull(Pricing.class, x.path("pricing").asString(null)),
            strings(x.path("platforms")),
            x.has("openSource") ? x.path("openSource").asBoolean() : null,
            date(pub, x.path("publishedPrecision").asString("INSTANT"), sourceId),
            date(upd, x.path("updatedPrecision").asString("INSTANT"), sourceId),
            popularity,
            retrieved,
            0,
            eventDay(rs.getTimestamp("source_event_starts_at"), sourceId),
            eventDay(rs.getTimestamp("source_event_ends_at"), sourceId),
            x.path("location").asString(null),
            enumOrNull(EventKind.class, x.path("eventKind").asString(null)),
            enumOrNull(EventFormat.class, x.path("eventFormat").asString(null)))
        .withVitality(statedVitality(x.path("vitality")));
  }

  private static Vitality statedVitality(JsonNode stored) {
    return new Vitality(
        stated(stored, "archived"),
        stated(stored, "fork"),
        stated(stored, "deprecated"),
        stated(stored, "running"));
  }

  private static Boolean stated(JsonNode vitality, String fact) {
    return vitality.has(fact) ? vitality.path(fact).asBoolean() : null;
  }

  private static DateFact eventDay(Timestamp t, String sourceId) {
    return t == null
        ? DateFact.unknown()
        : new DateFact(t.toInstant(), DatePrecision.DAY, sourceId, List.of(), false);
  }

  private static DateFact date(Instant value, String precision, String sourceId) {
    if (value == null) {
      return DateFact.unknown();
    }
    DatePrecision p = "DAY".equals(precision) ? DatePrecision.DAY : DatePrecision.INSTANT;
    return new DateFact(value, p, sourceId, List.of(), false);
  }

  private static Set<String> strings(JsonNode arr) {
    Set<String> out = new LinkedHashSet<>();
    arr.forEach(n -> out.add(n.asString()));
    return out;
  }

  static String clip(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  static <E extends Enum<E>> E enumOrNull(Class<E> type, String v) {
    return known(type, v, null);
  }

  private static boolean originIsKnown(String origin) {
    return known(ValueOrigin.class, origin, null) != null;
  }

  private static <E extends Enum<E>> E known(Class<E> type, String value, E whenUnknown) {
    if (value == null) {
      return whenUnknown;
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      return whenUnknown;
    }
  }
}
