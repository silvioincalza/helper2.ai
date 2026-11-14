package ai.helper2.catalog.internal;

import ai.helper2.catalog.CatalogLimits;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.CategoryLabels;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.search.LanguageDetector;
import ai.helper2.search.MergedResult;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.search.Vitality;
import ai.helper2.shared.CategoryInput;
import ai.helper2.shared.CategoryMatcher;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Repository
class JdbcCatalogWriter implements CatalogWriter, CategoryLabels {

  static final String ORIGIN_RANK =
      "(CASE %s WHEN 'DEMO' THEN 3 WHEN 'CURATED' THEN 2 WHEN 'INDEXED' THEN 1 ELSE 0 END)";

  static final int MAX_TAGS = 20;

  private final JdbcClient jdbc;
  private final JdbcTemplate template;
  private final ObjectMapper json;
  private final LanguageDetector languages;
  private final CategoryMatcher categories;
  private final ReviewQueue reviewQueue;
  private final Reconciliation reconciliation;

  JdbcCatalogWriter(
      JdbcClient jdbc,
      JdbcTemplate template,
      ObjectMapper json,
      LanguageDetector languages,
      CategoryMatcher categories,
      ReviewQueue reviewQueue,
      Reconciliation reconciliation) {
    this.jdbc = jdbc;
    this.template = template;
    this.json = json;
    this.languages = languages;
    this.categories = categories;
    this.reviewQueue = reviewQueue;
    this.reconciliation = reconciliation;
  }

  @Transactional
  @Override
  public void registerSources(Collection<SourceDescriptor> descriptors) {
    for (SourceDescriptor d : descriptors) {
      jdbc.sql(
              """
              INSERT INTO source (id, mode) VALUES (:id, :mode)
              ON CONFLICT (id) DO UPDATE SET mode = EXCLUDED.mode
              """)
          .param("id", d.getId())
          .param("mode", d.getMode().name())
          .update();
    }
  }

  private record RecordInput(UUID id, SourceHit hit, String externalId, UUID owner) {}

  private record ResourceInput(UUID id, Object[] args) {}

  @Transactional
  @Override
  public List<UUID> upsert(
      List<MergedResult> results, DataOrigin origin, Set<String> persistableSources) {
    Map<String, ResourceInput> resources = new TreeMap<>();
    Map<String, RecordInput> records = new TreeMap<>();
    for (MergedResult r : results) {
      List<SourceHit> own =
          r.getHits().stream().filter(h -> persistableSources.contains(h.getSourceId())).toList();
      if (own.isEmpty()) {
        continue;
      }
      resources.put(
          r.getCanonicalUrl().getValue(),
          new ResourceInput(r.getId().getValue(), resourceArgs(r, origin)));
      for (SourceHit h : own) {
        String externalId = ResourceRows.clip(h.getExternalId(), 300);
        UUID recordId =
            UUID.nameUUIDFromBytes((h.getSourceId() + "|" + h.getExternalId()).getBytes());
        records.put(
            h.getSourceId() + "\u001f" + externalId,
            new RecordInput(recordId, h, externalId, r.getId().getValue()));
      }
    }
    if (resources.isEmpty()) {
      return List.of();
    }
    Set<UUID> owners = new HashSet<>();
    for (RecordInput in : records.values()) {
      owners.add(in.owner());
    }
    upsertResources(
        resources.values().stream()
            .filter(in -> owners.contains(in.id()))
            .map(ResourceInput::args)
            .toList());

    List<UUID> changed = new ArrayList<>();
    List<UpsertedRecord> moves = new ArrayList<>();
    upsertRecords(records.values(), origin)
        .forEach(row -> {
          if (row.changed()) {
            changed.add(row.id());
          }
          if (row.previousOwner() != null && !row.previousOwner().equals(row.owner())) {
            moves.add(row);
          }
        });
    Set<UUID> movedFrom = new HashSet<>();
    moves.forEach(m -> movedFrom.add(m.previousOwner()));
    moveReportsOfEmptiedResources(moves);
    deleteOrphans(movedFrom);
    Set<UUID> touched = new HashSet<>(owners);
    touched.addAll(movedFrom);
    replaceLabels(touched);
    reconciliation.reconcile(touched, origin);
    refreshPackageTerms(touched);
    assignCategories(touched);
    return changed;
  }

  private static boolean isSeenAtItsOwnAddress(MergedResult r) {
    return r.getHits().stream().anyMatch(h -> h.getCanonicalUrl().equals(r.getCanonicalUrl()));
  }

  private static final String STORED_ORIGIN_IS_STRONGER =
      ORIGIN_RANK.formatted("resource.data_origin")
          + " > "
          + ORIGIN_RANK.formatted("EXCLUDED.data_origin");

  private static final String STORED_RESOURCE_KEEPS_WHAT_IT_SHOWS =
      "(" + STORED_ORIGIN_IS_STRONGER + " OR NOT (SELECT seen_at_its_own_address FROM incoming))";

  private void upsertResources(List<Object[]> args) {
    if (args.isEmpty()) {
      return;
    }
    template.batchUpdate(
        """
        WITH incoming (seen_at_its_own_address) AS (VALUES (CAST(? AS boolean)))
        INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience,
            tech_level, license, pricing, open_source, published_at, published_precision, published_source_id,
            updated_at, updated_source_id, data_origin, language)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (canonical_url) DO UPDATE SET
            last_seen_at = now(),
            modified_at = now(),
            url = CASE WHEN %2$s THEN resource.url ELSE EXCLUDED.url END,
            title = CASE WHEN %2$s THEN resource.title ELSE EXCLUDED.title END,
            description = CASE WHEN %2$s THEN resource.description ELSE coalesce(EXCLUDED.description, resource.description) END,
            description_origin = CASE WHEN %2$s OR EXCLUDED.description IS NULL THEN resource.description_origin ELSE EXCLUDED.description_origin END,
            type = CASE WHEN 'type' = ANY(resource.curated_fields) OR %1$s THEN resource.type ELSE EXCLUDED.type END,
            audience = CASE WHEN 'audience' = ANY(resource.curated_fields) OR %1$s THEN resource.audience ELSE EXCLUDED.audience END,
            tech_level = CASE WHEN 'techLevel' = ANY(resource.curated_fields) THEN resource.tech_level ELSE coalesce(resource.tech_level, EXCLUDED.tech_level) END,
            license = CASE WHEN 'license' = ANY(resource.curated_fields) OR %1$s THEN resource.license ELSE coalesce(EXCLUDED.license, resource.license) END,
            pricing = CASE WHEN 'pricing' = ANY(resource.curated_fields) OR %1$s THEN resource.pricing ELSE coalesce(EXCLUDED.pricing, resource.pricing) END,
            open_source = CASE WHEN 'openSource' = ANY(resource.curated_fields) OR %1$s THEN resource.open_source ELSE coalesce(EXCLUDED.open_source, resource.open_source) END,
            published_at = CASE WHEN %1$s OR EXCLUDED.published_at IS NULL THEN resource.published_at ELSE EXCLUDED.published_at END,
            published_precision = CASE WHEN %1$s OR EXCLUDED.published_at IS NULL THEN resource.published_precision ELSE EXCLUDED.published_precision END,
            published_source_id = CASE WHEN %1$s OR EXCLUDED.published_at IS NULL THEN resource.published_source_id ELSE EXCLUDED.published_source_id END,
            updated_at = CASE WHEN EXCLUDED.updated_at IS NOT NULL AND (resource.updated_at IS NULL OR EXCLUDED.updated_at > resource.updated_at)
                              THEN EXCLUDED.updated_at ELSE resource.updated_at END,
            updated_source_id = CASE WHEN EXCLUDED.updated_at IS NOT NULL AND (resource.updated_at IS NULL OR EXCLUDED.updated_at > resource.updated_at)
                              THEN EXCLUDED.updated_source_id ELSE resource.updated_source_id END,
            data_origin = CASE WHEN %1$s THEN resource.data_origin ELSE EXCLUDED.data_origin END,
            language = CASE WHEN %2$s THEN resource.language ELSE EXCLUDED.language END
        """
            .formatted(STORED_ORIGIN_IS_STRONGER, STORED_RESOURCE_KEEPS_WHAT_IT_SHOWS),
        args);
  }

  private void refreshPackageTerms(Set<UUID> resources) {
    if (resources.isEmpty()) {
      return;
    }
    jdbc.sql(
            """
            WITH terms AS (
              SELECT r.id,
                     left(nullif((SELECT string_agg(DISTINCT w, ' ' ORDER BY w)
                                  FROM source_record sr,
                                       regexp_split_to_table(lower(coalesce(sr.extra -> 'package' ->> 'terms', '')), '[^a-z0-9]+') AS w
                                  WHERE sr.resource_id = r.id AND w <> ''), ''), 2000) AS terms
              FROM resource r
              WHERE r.id = ANY(:ids)
            )
            UPDATE resource r SET package_terms = t.terms
            FROM terms t
            WHERE r.id = t.id AND r.package_terms IS DISTINCT FROM t.terms
            """)
        .param("ids", resources.toArray(UUID[]::new))
        .update();
  }

  private static @Nullable String licenseInItsShortSpelling(SourceHit h) {
    return h.getLicense() == null ? null : ResultMerger.normalizeLicense(h.getLicense());
  }

  private record UpsertedRecord(UUID id, UUID owner, boolean changed, UUID previousOwner) {}

  private List<UpsertedRecord> upsertRecords(Collection<RecordInput> records, DataOrigin origin) {
    ArrayNode rows = json.createArrayNode();
    for (RecordInput in : records) {
      SourceHit h = in.hit();
      ObjectNode n = rows.addObject();
      n.put("id", in.id().toString());
      n.put("source_id", h.getSourceId());
      n.put("external_id", in.externalId());
      n.put("resource_id", in.owner().toString());
      n.put("source_url", h.getUrl().toString());
      n.put("canonical_url", h.getCanonicalUrl().getValue());
      n.put(
          "repository_url", h.getRepositoryUrl() == null ? null : h.getRepositoryUrl().getValue());
      n.put("title", ResourceRows.clip(h.getTitle(), CatalogLimits.TITLE_CHARS));
      n.put("description", ResourceRows.clip(h.getDescription(), 4000));
      n.put("type", h.getType().name());
      n.put("license", licenseInItsShortSpelling(h));
      n.put("source_published_at", iso(h.getPublished().getValue()));
      n.put("source_updated_at", iso(h.getUpdated().getValue()));
      n.put("source_event_starts_at", iso(h.getEventStart().getValue()));
      n.put("source_event_ends_at", iso(h.getEventEnd().getValue()));
      n.put("retrieved_at", iso(h.getRetrievedAt()));
      n.put("fingerprint", Fingerprints.of(h));
      n.set("extra", extra(h).put("dataOrigin", origin.name()));
    }
    return jdbc.sql(
            """
            WITH input AS (
              SELECT * FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS t(
                id uuid, source_id text, external_id text, resource_id uuid, source_url text, canonical_url text,
                repository_url text, title text, description text, type text, license text,
                source_published_at timestamptz, source_updated_at timestamptz, source_event_starts_at timestamptz,
                source_event_ends_at timestamptz, retrieved_at timestamptz, fingerprint text, extra jsonb)
            ), old AS (
              SELECT sr.source_id, sr.external_id, sr.fingerprint, sr.resource_id
              FROM source_record sr JOIN input i ON i.source_id = sr.source_id AND i.external_id = sr.external_id
            ), up AS (
              INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url,
                  repository_url, title, description, type, license, source_published_at, source_updated_at,
                  source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
              SELECT id, source_id, external_id, resource_id, source_url, canonical_url, repository_url, title,
                     description, type, license, source_published_at, source_updated_at, source_event_starts_at,
                     source_event_ends_at, retrieved_at, fingerprint, extra
              FROM input
              ON CONFLICT (source_id, external_id) DO UPDATE SET
                  resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
                  canonical_url = EXCLUDED.canonical_url, repository_url = EXCLUDED.repository_url,
                  title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
                  license = EXCLUDED.license, source_published_at = EXCLUDED.source_published_at,
                  source_updated_at = EXCLUDED.source_updated_at, retrieved_at = EXCLUDED.retrieved_at,
                  source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
                  fingerprint = EXCLUDED.fingerprint,
                  extra = EXCLUDED.extra || jsonb_build_object('dataOrigin',
                      CASE WHEN %1$s > %2$s THEN source_record.extra ->> 'dataOrigin'
                           ELSE EXCLUDED.extra ->> 'dataOrigin' END)
                      || (SELECT CASE WHEN newest.vitality IS NULL THEN '{}'::jsonb
                                      ELSE jsonb_build_object('vitality', newest.vitality) END
                          FROM (SELECT CASE
                                         WHEN kept.vitality IS NULL THEN stated.vitality
                                         WHEN stated.vitality IS NULL THEN kept.vitality
                                         WHEN NOT jsonb_exists(stated.vitality, 'archived')
                                           THEN kept.vitality || stated.vitality
                                         WHEN CAST(kept.vitality ->> 'checkedAt' AS timestamptz)
                                              > CAST(stated.vitality ->> 'statedAt' AS timestamptz)
                                           THEN kept.vitality || stated.vitality
                                                || jsonb_build_object('archived', kept.vitality -> 'archived')
                                         ELSE (kept.vitality - 'checkedAt') || stated.vitality
                                       END AS vitality
                                FROM (SELECT CASE WHEN source_record.repository_url
                                                       IS NOT DISTINCT FROM EXCLUDED.repository_url
                                                  THEN source_record.extra -> 'vitality' END AS vitality) kept,
                                     (SELECT EXCLUDED.extra -> 'vitality' AS vitality) stated) newest)
              RETURNING id, source_id, external_id, resource_id, fingerprint
            )
            SELECT up.id, up.resource_id, old.fingerprint IS DISTINCT FROM up.fingerprint AS changed,
                   old.resource_id AS previous_owner
            FROM up LEFT JOIN old ON old.source_id = up.source_id AND old.external_id = up.external_id
            """
                .formatted(
                    ORIGIN_RANK.formatted("(source_record.extra ->> 'dataOrigin')"),
                    ORIGIN_RANK.formatted("(EXCLUDED.extra ->> 'dataOrigin')")))
        .param("rows", json.writeValueAsString(rows))
        .query((rs, i) ->
            new UpsertedRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("resource_id", UUID.class),
                rs.getBoolean("changed"),
                rs.getObject("previous_owner", UUID.class)))
        .list();
  }

  @Override
  @Transactional
  public Forgotten forgetRecords(String sourceId, Collection<String> externalIds) {
    if (externalIds.isEmpty()) {
      return Forgotten.NONE;
    }
    String[] ids =
        externalIds.stream()
            .map(id -> ResourceRows.clip(id, 300).toLowerCase(Locale.ROOT))
            .toArray(String[]::new);
    Map<ClaimKind, UUID[]> claims = claimsSupportedBy(sourceId, ids);
    List<UUID> owners =
        jdbc.sql(
                """
                DELETE FROM source_record
                WHERE source_id = :source AND lower(external_id) = ANY(:ids)
                RETURNING resource_id
                """)
            .param("source", sourceId)
            .param("ids", ids)
            .query(UUID.class)
            .list();
    dropClaimsLeftWithoutEvidence(claims);
    return afterForgetting(owners);
  }

  private enum ClaimKind {
    ASSERTION(
        "field_assertion", "field_assertion_evidence", "assertion_id", "c.origin <> 'CURATED'"),
    RELATIONSHIP("relationship", "relationship_evidence", "relationship_id", "true"),
    SUMMARY("summary_claim", "summary_claim_evidence", "claim_id", "true");

    private final String table;
    private final String evidenceTable;
    private final String key;
    private final String removable;

    ClaimKind(String table, String evidenceTable, String key, String removable) {
      this.table = table;
      this.evidenceTable = evidenceTable;
      this.key = key;
      this.removable = removable;
    }
  }

  private Map<ClaimKind, UUID[]> claimsSupportedBy(String sourceId, String[] externalIds) {
    Map<ClaimKind, UUID[]> claims = new EnumMap<>(ClaimKind.class);
    for (ClaimKind kind : ClaimKind.values()) {
      claims.put(
          kind,
          jdbc.sql(
                  """
                  SELECT DISTINCT j.%s FROM %s j
                  JOIN evidence e ON e.id = j.evidence_id
                  JOIN source_record sr ON sr.id = e.source_record_id
                  WHERE sr.source_id = :source AND lower(sr.external_id) = ANY(:ids)
                  """
                      .formatted(kind.key, kind.evidenceTable))
              .param("source", sourceId)
              .param("ids", externalIds)
              .query(UUID.class)
              .list()
              .toArray(UUID[]::new));
    }
    return claims;
  }

  private void dropClaimsLeftWithoutEvidence(Map<ClaimKind, UUID[]> claims) {
    claims.forEach((kind, ids) -> {
      if (ids.length == 0) {
        return;
      }
      jdbc.sql(
              """
              DELETE FROM %1$s c
              WHERE c.id = ANY(:ids) AND %4$s
                AND NOT EXISTS (SELECT 1 FROM %2$s j WHERE j.%3$s = c.id)
              """
                  .formatted(kind.table, kind.evidenceTable, kind.key, kind.removable))
          .param("ids", ids)
          .update();
    });
  }

  @Transactional
  Forgotten afterForgetting(List<UUID> owners) {
    if (owners.isEmpty()) {
      return Forgotten.NONE;
    }
    Set<UUID> touched = new LinkedHashSet<>(owners);
    List<UUID> removed = deleteEmptied(touched);
    touched.removeAll(removed);
    replaceLabels(touched);
    reconciliation.reconcile(touched, DataOrigin.INDEXED);
    refreshPackageTerms(touched);
    assignCategories(touched);
    return new Forgotten(owners.size(), removed.size(), List.copyOf(touched));
  }

  private List<UUID> deleteEmptied(Collection<UUID> candidates) {
    List<UUID> locked =
        jdbc.sql(
                """
                SELECT r.id FROM resource r
                WHERE r.id = ANY(:ids) AND r.data_origin IN ('INDEXED', 'OBSERVED')
                  AND cardinality(r.curated_fields) = 0
                FOR UPDATE SKIP LOCKED
                """)
            .param("ids", candidates.toArray(UUID[]::new))
            .query(UUID.class)
            .list();
    if (locked.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            DELETE FROM resource r
            WHERE r.id = ANY(:ids)
              AND NOT EXISTS (SELECT 1 FROM source_record s WHERE s.resource_id = r.id)
              AND NOT EXISTS (SELECT 1 FROM issue_report i WHERE i.resource_id = r.id)
              AND NOT EXISTS (SELECT 1 FROM review_item v WHERE v.resource_id = r.id)
            RETURNING r.id
            """)
        .param("ids", locked.toArray(UUID[]::new))
        .query(UUID.class)
        .list();
  }

  private void moveReportsOfEmptiedResources(List<UpsertedRecord> moves) {
    if (moves.isEmpty()) {
      return;
    }
    UUID[] from = moves.stream().map(UpsertedRecord::previousOwner).toArray(UUID[]::new);
    UUID[] to = moves.stream().map(UpsertedRecord::owner).toArray(UUID[]::new);
    String target =
        """
        WITH moved AS (SELECT * FROM unnest(CAST(:from AS uuid[]), CAST(:to AS uuid[])) AS m(previous_owner, owner)),
        target AS (
          SELECT m.previous_owner, (array_agg(DISTINCT m.owner))[1] AS owner
          FROM moved m
          WHERE NOT EXISTS (SELECT 1 FROM source_record s WHERE s.resource_id = m.previous_owner)
          GROUP BY m.previous_owner HAVING count(DISTINCT m.owner) = 1
        )
        """;
    for (String table : List.of("issue_report", "review_item")) {
      jdbc.sql(
              target
                  + "UPDATE "
                  + table
                  + " x SET resource_id = t.owner FROM target t WHERE x.resource_id = t.previous_owner")
          .param("from", from)
          .param("to", to)
          .update();
    }
  }

  private void deleteOrphans(Collection<UUID> candidates) {
    if (candidates.isEmpty()) {
      return;
    }
    jdbc.sql(
            """
            DELETE FROM resource r
            WHERE r.id = ANY(:ids) AND r.data_origin = 'OBSERVED'
              AND cardinality(r.curated_fields) = 0
              AND NOT EXISTS (SELECT 1 FROM source_record s WHERE s.resource_id = r.id)
              AND NOT EXISTS (SELECT 1 FROM issue_report i WHERE i.resource_id = r.id)
              AND NOT EXISTS (SELECT 1 FROM review_item v WHERE v.resource_id = r.id)
            """)
        .param("ids", candidates.toArray(UUID[]::new))
        .update();
  }

  private void replaceLabels(Collection<UUID> resourceIds) {
    if (resourceIds.isEmpty()) {
      return;
    }
    UUID[] ids = resourceIds.toArray(UUID[]::new);
    jdbc.sql("DELETE FROM resource_label WHERE resource_id = ANY(:ids) AND kind <> 'CATEGORY'")
        .param("ids", ids)
        .update();
    jdbc.sql(
            """
            WITH labels AS (
              SELECT DISTINCT sr.resource_id, k.kind, btrim(left(btrim(v.value), 60)) AS value
              FROM source_record sr
              CROSS JOIN (VALUES ('TAG', 'tags'), ('USE_CASE', 'useCases'), ('PLATFORM', 'platforms')) AS k(kind, field)
              CROSS JOIN LATERAL jsonb_array_elements_text(CASE WHEN jsonb_typeof(sr.extra -> k.field) = 'array'
                                                                THEN sr.extra -> k.field ELSE '[]'::jsonb END) AS v(value)
              WHERE sr.resource_id = ANY(:ids) AND btrim(v.value) <> ''
            ), ranked AS (
              SELECT l.*, row_number() OVER (PARTITION BY l.resource_id, l.kind ORDER BY l.value) AS n FROM labels l
            )
            INSERT INTO resource_label (resource_id, kind, value)
            SELECT resource_id, kind, value FROM ranked WHERE kind <> 'TAG' OR n <= :maxTags
            ON CONFLICT DO NOTHING
            """)
        .param("ids", ids)
        .param("maxTags", MAX_TAGS)
        .update();
  }

  @Transactional
  @Override
  public int refreshCategories(Collection<UUID> resourceIds) {
    return assignCategories(resourceIds);
  }

  private int assignCategories(Collection<UUID> resourceIds) {
    if (resourceIds.isEmpty()) {
      return 0;
    }
    UUID[] ids = resourceIds.toArray(UUID[]::new);
    List<Object[]> labels = new ArrayList<>();
    Set<UUID> categorized = new HashSet<>();
    jdbc.sql(
            """
            SELECT r.id, r.title, r.type,
                   (SELECT string_agg(DISTINCT sr.description, E'\\n') FROM source_record sr
                    WHERE sr.resource_id = r.id AND sr.description IS NOT NULL
                      AND coalesce(sr.extra ->> 'descriptionOrigin', 'SOURCE_PROVIDED') NOT LIKE 'AI\\_%') AS description,
                   ARRAY(SELECT DISTINCT t.value FROM source_record sr
                         CROSS JOIN LATERAL jsonb_array_elements_text(
                             CASE WHEN jsonb_typeof(sr.extra -> 'tags') = 'array' THEN sr.extra -> 'tags'
                                  ELSE '[]'::jsonb END) AS t(value)
                         WHERE sr.resource_id = r.id) AS tags,
                   ARRAY(SELECT sr.source_id FROM source_record sr WHERE sr.resource_id = r.id
                         UNION
                         SELECT f ->> 'sourceId' FROM source_record sr
                         CROSS JOIN LATERAL jsonb_array_elements(
                             CASE WHEN jsonb_typeof(sr.extra -> 'foundBy') = 'array' THEN sr.extra -> 'foundBy'
                                  ELSE '[]'::jsonb END) AS f
                         WHERE sr.resource_id = r.id AND f ->> 'sourceId' IS NOT NULL) AS finders,
                   ARRAY(SELECT DISTINCT e.heading_path FROM awesome_list_entry e
                         WHERE e.resource_id = r.id AND e.heading_path <> '') AS headings
            FROM resource r WHERE r.id = ANY(:ids)
            """)
        .param("ids", ids)
        .query((rs, i) -> {
          UUID id = rs.getObject("id", UUID.class);
          List<String> found =
              categories.match(
                  new CategoryInput(
                      rs.getString("title"),
                      rs.getString("description"),
                      textArray(rs, "tags"),
                      ResourceRows.enumOrNull(ResourceType.class, rs.getString("type")),
                      textArray(rs, "finders"),
                      textArray(rs, "headings")));
          found.forEach(c -> labels.add(new Object[] {id, c}));
          if (!found.isEmpty()) {
            categorized.add(id);
          }
          return id;
        })
        .list();
    jdbc.sql("DELETE FROM resource_label WHERE resource_id = ANY(:ids) AND kind = 'CATEGORY'")
        .param("ids", ids)
        .update();
    template.batchUpdate(
        "INSERT INTO resource_label (resource_id, kind, value) VALUES (?, 'CATEGORY', ?)"
            + " ON CONFLICT DO NOTHING",
        labels);
    return categorized.size();
  }

  private static List<String> textArray(ResultSet rs, String column) throws SQLException {
    java.sql.Array array = rs.getArray(column);
    if (array == null) {
      return List.of();
    }
    return Arrays.stream((Object[]) array.getArray())
        .filter(Objects::nonNull)
        .map(Object::toString)
        .toList();
  }

  @Override
  public List<UUID> resourceIdsAfter(@Nullable UUID after, int limit) {
    return jdbc.sql("SELECT id FROM resource WHERE id > :after ORDER BY id LIMIT :limit")
        .param("after", after == null ? new UUID(0, 0) : after)
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  private Object[] resourceArgs(MergedResult r, DataOrigin origin) {
    DateFact pub = r.getPublished();
    DateFact upd = r.getUpdated();
    return new Object[] {
      isSeenAtItsOwnAddress(r),
      r.getId().getValue(),
      r.getCanonicalUrl().getValue(),
      r.getUrl().toString(),
      ResourceRows.clip(r.getTitle(), CatalogLimits.TITLE_CHARS),
      ResourceRows.clip(r.getDescription(), 4000),
      r.getDescriptionOrigin().name(),
      r.getType().name(),
      r.getAudience().name(),
      r.getTechLevel() == null ? null : r.getTechLevel().name(),
      r.getLicense(),
      r.getPricing() == null ? null : r.getPricing().name(),
      r.getOpenSource(),
      ts(pub.getValue()),
      pub.getValue() == null ? "UNKNOWN" : pub.getPrecision().name(),
      pub.getValue() == null ? null : pub.getSourceId(),
      ts(upd.getValue()),
      upd.getValue() == null ? null : upd.getSourceId(),
      origin.name(),
      languageOf(r),
    };
  }

  private String languageOf(MergedResult r) {
    String text =
        Objects.toString(ResourceRows.clip(r.getTitle(), CatalogLimits.TITLE_CHARS), "")
            + " "
            + Objects.toString(ResourceRows.clip(r.getDescription(), 1000), "");
    String code = languages.detect(text);
    return code == null ? "simple" : code;
  }

  private static void putStatedVitality(ObjectNode extra, SourceHit h) {
    Vitality stated = h.getVitality();
    if (Vitality.UNKNOWN.equals(stated)) {
      return;
    }
    ObjectNode vitality = extra.putObject("vitality");
    putIfStated(vitality, "archived", stated.getArchived());
    putIfStated(vitality, "fork", stated.getFork());
    putIfStated(vitality, "deprecated", stated.getDeprecated());
    putIfStated(vitality, "running", stated.getRunning());
    vitality.put("statedAt", h.getRetrievedAt().toString());
  }

  private static void putIfStated(ObjectNode vitality, String fact, @Nullable Boolean stated) {
    if (stated != null) {
      vitality.put(fact, stated);
    }
  }

  private ObjectNode extra(SourceHit h) {
    ObjectNode n = json.createObjectNode();
    n.put("descriptionOrigin", h.getDescriptionOrigin().name());
    n.put("audience", h.getAudience().name());
    n.put("publishedPrecision", h.getPublished().getPrecision().name());
    n.put("updatedPrecision", h.getUpdated().getPrecision().name());
    if (h.getTechLevel() != null) n.put("techLevel", h.getTechLevel().name());
    if (h.getPricing() != null) n.put("pricing", h.getPricing().name());
    if (h.getOpenSource() != null) n.put("openSource", h.getOpenSource());
    n.putPOJO("tags", h.getTags());
    n.putPOJO("useCases", h.getUseCases());
    n.putPOJO("platforms", h.getPlatforms());
    if (h.getLocation() != null) n.put("location", h.getLocation());
    if (h.getEventKind() != null) n.put("eventKind", h.getEventKind().name());
    if (h.getEventFormat() != null) n.put("eventFormat", h.getEventFormat().name());
    putStatedVitality(n, h);
    var pop = n.putArray("popularity");
    for (PopularityMetric m : h.getPopularity()) {
      pop.addObject()
          .put("kind", m.getKind().name())
          .put("value", m.getValue())
          .put("asOf", m.getAsOf().toString());
    }
    h.getFacts()
        .forEach((key, value) -> {
          if (!n.has(key)) {
            n.set(key, json.valueToTree(value));
          }
        });
    return n;
  }

  private static String iso(Instant i) {
    return i == null ? null : i.toString();
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  @Override
  @Transactional
  public Optional<UUID> report(
      UUID resourceId,
      String kind,
      @Nullable String field,
      String message,
      String reason,
      String note) {
    boolean exists =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE id = :id)")
            .param("id", resourceId)
            .query(Boolean.class)
            .single();
    if (!exists) {
      return Optional.empty();
    }
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO issue_report (id, resource_id, kind, field, message)
            VALUES (:id, :rid, :kind, :field, :message)
            """)
        .param("id", id)
        .param("rid", resourceId)
        .param("kind", kind)
        .param("field", field)
        .param("message", message)
        .update();
    reviewQueue.open(
        ReviewQueue.Kind.USER_REPORT,
        resourceId,
        reason,
        note,
        new ReviewQueue.Links(null, null, id));
    return Optional.of(id);
  }
}
