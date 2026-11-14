package ai.helper2.catalog.internal;

import ai.helper2.catalog.EnrichmentFacts;
import ai.helper2.catalog.JobMarkers;
import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.ScoutPolicy;
import ai.helper2.catalog.enrichment.EnrichmentResults;
import ai.helper2.catalog.enrichment.StoredEnrichments;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcEnrichmentResults
    implements EnrichmentResults, EnrichmentFacts, StoredEnrichments {

  private final JdbcClient jdbc;
  private final ReviewQueue reviewQueue;
  private final ObjectMapper json;
  private final Clock clock;
  private final JobMarkers markers;
  private final JdbcKnownTargets knownTargets;

  JdbcEnrichmentResults(
      JdbcClient jdbc,
      ReviewQueue reviewQueue,
      ObjectMapper json,
      Clock clock,
      JobMarkers markers) {
    this.jdbc = jdbc;
    this.reviewQueue = reviewQueue;
    this.json = json;
    this.clock = clock;
    this.markers = markers;
    this.knownTargets = new JdbcKnownTargets(jdbc);
  }

  @Override
  public List<UUID> judgedAfter(@Nullable UUID after, int limit) {
    return jdbc.sql(
            """
            SELECT id FROM resource
            WHERE enrichment_status IN ('VALIDATED', 'PARTIAL', 'REJECTED')
              AND (CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid))
            ORDER BY id LIMIT :limit
            """)
        .param("after", after)
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  @Override
  public Stored stored(UUID resourceId) {
    String[] resource =
        jdbc.sql("SELECT title, type FROM resource WHERE id = :id")
            .param("id", resourceId)
            .query((rs, n) -> new String[] {rs.getString("title"), rs.getString("type")})
            .optional()
            .orElse(new String[] {"", "OTHER"});
    String title = resource[0];
    Map<UUID, StoredField> fields = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT f.id, f.field, f.value, f.origin, f.verification, e.excerpt FROM field_assertion f
            LEFT JOIN field_assertion_evidence x ON x.assertion_id = f.id
            LEFT JOIN evidence e ON e.id = x.evidence_id
            WHERE f.resource_id = :id AND f.origin <> 'CURATED' ORDER BY f.id
            """)
        .param("id", resourceId)
        .query(rs -> {
          UUID id = rs.getObject("id", UUID.class);
          StoredField f =
              fields.computeIfAbsent(
                  id,
                  k -> {
                    try {
                      return new StoredField(
                          k,
                          rs.getString("field"),
                          rs.getString("value"),
                          ValueOrigin.valueOf(rs.getString("origin")),
                          Verification.valueOf(rs.getString("verification")),
                          new ArrayList<>());
                    } catch (java.sql.SQLException e) {
                      throw new IllegalStateException(e);
                    }
                  });
          String excerpt = rs.getString("excerpt");
          if (excerpt != null) {
            f.excerpts().add(excerpt);
          }
        });
    Map<UUID, StoredClaim> claims = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT c.id, c.text, e.excerpt FROM summary_claim c
            LEFT JOIN summary_claim_evidence x ON x.claim_id = c.id
            LEFT JOIN evidence e ON e.id = x.evidence_id
            WHERE c.resource_id = :id ORDER BY c.id
            """)
        .param("id", resourceId)
        .query(rs -> {
          UUID id = rs.getObject("id", UUID.class);
          String text = rs.getString("text");
          StoredClaim c =
              claims.computeIfAbsent(id, k -> new StoredClaim(k, text, new ArrayList<>()));
          String excerpt = rs.getString("excerpt");
          if (excerpt != null) {
            c.excerpts().add(excerpt);
          }
        });
    String promptVersion =
        jdbc.sql(
                """
                SELECT prompt_version FROM enrichment_run
                WHERE resource_id = :id AND outcome IN ('VALIDATED', 'PARTIAL', 'REJECTED')
                ORDER BY started_at DESC LIMIT 1
                """)
            .param("id", resourceId)
            .query(String.class)
            .optional()
            .orElse(null);
    return new Stored(
        resourceId,
        title,
        resource[1],
        promptVersion,
        List.copyOf(fields.values()),
        List.copyOf(claims.values()));
  }

  @Override
  @Transactional
  public int rejectFields(List<UUID> fieldIds) {
    if (fieldIds.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            "UPDATE field_assertion SET verification = 'REJECTED' WHERE id IN (:ids) AND verification <> 'REJECTED'")
        .param("ids", fieldIds)
        .update();
  }

  @Override
  @Transactional
  public int dropClaims(List<UUID> claimIds) {
    if (claimIds.isEmpty()) {
      return 0;
    }
    List<UUID> evidence =
        jdbc.sql("SELECT DISTINCT evidence_id FROM summary_claim_evidence WHERE claim_id IN (:ids)")
            .param("ids", claimIds)
            .query(UUID.class)
            .list();
    int dropped =
        jdbc.sql("DELETE FROM summary_claim WHERE id IN (:ids)").param("ids", claimIds).update();
    deleteOrphanEvidence(evidence);
    return dropped;
  }

  @Override
  public int closeItemsOfUnstatedValues(String by, String note) {
    return jdbc.sql(
            """
            UPDATE review_item i
            SET status = 'SUPERSEDED', resolved_at = now(), resolved_by = :by, resolution_note = :note
            FROM enrichment_run r
            WHERE i.kind = 'ENRICHMENT_VALIDATION' AND i.status = 'OPEN' AND r.id = i.enrichment_run_id
              AND jsonb_array_length(r.validation_errors) > 0
              AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements_text(r.validation_errors) AS e(line)
                              WHERE right(e.line, length(:unstated)) <> :unstated)
              AND NOT EXISTS (SELECT 1 FROM field_assertion f
                              WHERE f.enrichment_run_id = r.id AND f.verification = 'CONFLICTING')
            """)
        .param("by", by)
        .param("note", note)
        .param("unstated", ScoutPolicy.UNSTATED_VALUE)
        .update();
  }

  @Override
  public void requestRun(UUID resourceId) {
    jdbc.sql(
            "UPDATE resource SET enrichment_status = 'NOT_RUN', enrichment_rerun_requested_at = now() WHERE id = :id")
        .param("id", resourceId)
        .update();
  }

  @Override
  public boolean done(String name) {
    return markers.marker(name).isPresent();
  }

  @Override
  public void markDone(String name) {
    markers.mark(name, "done");
  }

  @Override
  @Transactional
  public UUID record(Run run, @Nullable Checked checked) {
    if (checked != null) {
      KnownTargetLock.forUse(jdbc);
    }
    UUID runId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO enrichment_run (id, resource_id, provider, model, prompt_version, input_fingerprint,
                                        started_at, finished_at, outcome, validation_errors, extra_evidence)
            VALUES (:id, :rid, :provider, :model, :pv, :fp, :started, :finished, :outcome, CAST(:errors AS jsonb), :extra)
            """)
        .param("id", runId)
        .param("rid", run.resourceId())
        .param("provider", run.provider())
        .param("model", run.model())
        .param("pv", run.promptVersion())
        .param("fp", run.inputFingerprint())
        .param("started", Timestamp.from(run.startedAt()))
        .param("finished", Timestamp.from(clock.instant()))
        .param("outcome", run.outcome())
        .param("errors", json.writeValueAsString(run.errors()))
        .param("extra", run.extraEvidence())
        .update();
    jdbc.sql("UPDATE resource SET enrichment_status = :s WHERE id = :id")
        .param("s", "FAILED".equals(run.outcome()) ? "REJECTED" : run.outcome())
        .param("id", run.resourceId())
        .update();
    if (checked == null) {
      return runId;
    }
    UUID resourceId = run.resourceId();
    reviewQueue.supersedeEnrichmentItems(resourceId, runId);
    deleteAiFields(resourceId);
    deleteUnreviewedAiRelationships(resourceId);
    Map<String, UUID> evidence = new HashMap<>();
    for (Field f : checked.fields()) {
      if (f.verification() == Verification.REJECTED) {
        continue;
      }
      UUID id = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO field_assertion (id, resource_id, field, value, origin, verification, enrichment_run_id)
              VALUES (:id, :rid, :f, :v, :origin, :ver, :run)
              """)
          .param("id", id)
          .param("rid", resourceId)
          .param("f", f.field())
          .param("v", f.value())
          .param("origin", f.origin().name())
          .param("ver", f.verification().name())
          .param("run", runId)
          .update();
      if (!linkedEvidenceStands(
          "field_assertion_evidence", "assertion_id", id, f.citations(), evidence)) {
        drop("field_assertion", id);
      }
    }
    Map<String, KnownTarget> known = knownNow(checked.relationships());
    for (Relationship found : checked.relationships()) {
      if (found.verification() == Verification.REJECTED) {
        continue;
      }
      Relationship r = asKnownNow(found, known);
      UUID id = UUID.randomUUID();
      int inserted =
          jdbc.sql(
                  """
                  INSERT INTO relationship (id, from_resource_id, to_resource_id, to_title, kind, verification, origin,
                                            enrichment_run_id, target_kind, stated_target_kind)
                  VALUES (:id, :from, :to, :title, :kind, :ver, :origin, :run, :targetKind, :statedKind)
                  ON CONFLICT (from_resource_id, kind, to_title) DO NOTHING
                  """)
              .param("id", id)
              .param("from", resourceId)
              .param("to", r.targetId())
              .param("title", r.target())
              .param("kind", r.kind())
              .param("origin", r.origin().name())
              .param("ver", r.verification().name())
              .param("run", runId)
              .param("targetKind", r.knownKind() == null ? null : r.knownKind().name())
              .param("statedKind", r.statedKind())
              .update();
      if (inserted == 0) {
        continue;
      }
      if (!linkedEvidenceStands(
          "relationship_evidence", "relationship_id", id, r.citations(), evidence)) {
        drop("relationship", id);
        continue;
      }
      if (r.verification() != Verification.VERIFIED) {
        reviewQueue.open(
            ReviewQueue.Kind.RELATIONSHIP_CANDIDATE,
            resourceId,
            CandidateText.reason(r.kind(), r.target()),
            whyUnverified(found, r),
            new ReviewQueue.Links(runId, id, null));
      }
    }
    int pos = 0;
    for (Claim c : checked.summary()) {
      UUID id = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO summary_claim (id, resource_id, enrichment_run_id, position, text, self_declared)
              VALUES (:id, :rid, :run, :p, :t, :self)
              """)
          .param("id", id)
          .param("rid", resourceId)
          .param("run", runId)
          .param("p", pos++)
          .param("t", c.text())
          .param("self", c.selfDeclared())
          .update();
      if (!linkedEvidenceStands(
          "summary_claim_evidence", "claim_id", id, c.citations(), evidence)) {
        drop("summary_claim", id);
        pos--;
      }
    }
    if (run.needsReview()
        || checked.fields().stream().anyMatch(f -> f.verification() == Verification.CONFLICTING)) {
      reviewQueue.open(
          ReviewQueue.Kind.ENRICHMENT_VALIDATION,
          resourceId,
          "Enrichment "
              + run.outcome().toLowerCase()
              + ": "
              + run.errors().size()
              + " validation issue(s)",
          String.join("\n", run.errors().stream().limit(20).toList()),
          new ReviewQueue.Links(runId, null, null));
    }
    return runId;
  }

  private Map<String, KnownTarget> knownNow(List<Relationship> relationships) {
    List<String> names =
        relationships.stream()
            .filter(r -> r.knownKind() != null)
            .map(Relationship::target)
            .toList();
    if (names.isEmpty()) {
      return Map.of();
    }
    return knownTargets.named(names);
  }

  private static Relationship asKnownNow(Relationship r, Map<String, KnownTarget> known) {
    if (r.knownKind() == null) {
      return r;
    }
    KnownTarget now = known.get(KnownTarget.key(r.target()));
    return new Relationship(
        r.kind(),
        r.target(),
        r.targetId(),
        now == null ? Verification.UNVERIFIED : r.verification(),
        r.citations(),
        r.origin(),
        now == null ? null : now.kind(),
        r.statedKind());
  }

  private static String whyUnverified(Relationship found, Relationship saved) {
    if (found.knownKind() != null) {
      return KnownTarget.noLonger(found.knownKind());
    }
    return saved.origin() == ValueOrigin.SELF_DECLARED
        ? CandidateText.SELF_DECLARED
        : CandidateText.UNKNOWN_TARGET;
  }

  @Override
  public List<ResourceDetail.Assertion> assertions(UUID resourceId) {
    List<Owned<ResourceDetail.Assertion>> rows =
        jdbc.sql(
                "SELECT id, field, value, origin, verification FROM field_assertion WHERE resource_id = :id ORDER BY field")
            .param("id", resourceId)
            .query((rs, n) -> {
              String field = rs.getString("field");
              String value = rs.getString("value");
              ValueOrigin origin = ValueOrigin.valueOf(rs.getString("origin"));
              Verification verification = Verification.valueOf(rs.getString("verification"));
              return new Owned<>(
                  rs.getObject("id", UUID.class),
                  evidence ->
                      new ResourceDetail.Assertion(
                          field, value, origin, verification, null, evidence));
            })
            .list();
    return withEvidence(rows, "field_assertion_evidence", "assertion_id");
  }

  @Override
  public List<ResourceDetail.Relationship> relationships(UUID resourceId) {
    List<Owned<ResourceDetail.Relationship>> rows =
        jdbc.sql(
                """
                SELECT id, kind, to_resource_id, to_title, verification, origin FROM relationship
                WHERE from_resource_id = :id AND verification <> 'REJECTED' ORDER BY kind, to_title
                """)
            .param("id", resourceId)
            .query((rs, n) -> {
              String kind = rs.getString("kind");
              UUID target = rs.getObject("to_resource_id", UUID.class);
              String title = rs.getString("to_title");
              Verification verification = Verification.valueOf(rs.getString("verification"));
              ValueOrigin origin = ValueOrigin.valueOf(rs.getString("origin"));
              return new Owned<>(
                  rs.getObject("id", UUID.class),
                  evidence ->
                      new ResourceDetail.Relationship(
                          kind, target, title, verification, evidence, origin));
            })
            .list();
    return withEvidence(rows, "relationship_evidence", "relationship_id");
  }

  @Override
  public ResourceDetail.@Nullable Summary summary(UUID resourceId) {
    return jdbc.sql(
            """
            SELECT r.id, r.model, r.prompt_version, r.finished_at FROM enrichment_run r
            WHERE r.resource_id = :id AND r.outcome IN ('VALIDATED','PARTIAL')
              AND EXISTS (SELECT 1 FROM summary_claim c WHERE c.enrichment_run_id = r.id)
            ORDER BY r.started_at DESC LIMIT 1
            """)
        .param("id", resourceId)
        .query((rs, n) ->
            new RunRow(
                rs.getObject("id", UUID.class),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getTimestamp("finished_at").toInstant()))
        .optional()
        .map(run -> {
          List<Owned<ResourceDetail.SummaryClaim>> rows =
              jdbc.sql(
                      "SELECT id, text, self_declared FROM summary_claim WHERE enrichment_run_id = :run ORDER BY position")
                  .param("run", run.id())
                  .query((c, m) -> {
                    String text = c.getString("text");
                    boolean selfDeclared = c.getBoolean("self_declared");
                    return new Owned<>(
                        c.getObject("id", UUID.class),
                        evidence -> new ResourceDetail.SummaryClaim(text, evidence, selfDeclared));
                  })
                  .list();
          return new ResourceDetail.Summary(
              withEvidence(rows, "summary_claim_evidence", "claim_id"),
              run.model(),
              run.promptVersion(),
              run.finishedAt());
        })
        .orElse(null);
  }

  private record RunRow(UUID id, String model, String promptVersion, Instant finishedAt) {}

  private record Owned<T>(UUID id, Function<List<ResourceDetail.Evidence>, T> build) {}

  private <T> List<T> withEvidence(List<Owned<T>> rows, String joinTable, String fk) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<ResourceDetail.Evidence>> evidence =
        evidenceOf(joinTable, fk, rows.stream().map(Owned::id).toList());
    return rows.stream()
        .map(r -> r.build().apply(evidence.getOrDefault(r.id(), List.of())))
        .toList();
  }

  private Map<UUID, List<ResourceDetail.Evidence>> evidenceOf(
      String joinTable, String fk, List<UUID> owners) {
    Map<UUID, List<ResourceDetail.Evidence>> byOwner = new HashMap<>();
    jdbc.sql(
            """
            SELECT j.%2$s AS owner, e.id, e.source_url, e.retrieved_at, e.excerpt FROM evidence e
            JOIN %1$s j ON j.evidence_id = e.id WHERE j.%2$s = ANY(:owners) ORDER BY j.%2$s, e.retrieved_at
            """
                .formatted(joinTable, fk))
        .param("owners", owners.toArray(UUID[]::new))
        .query(rs -> {
          byOwner
              .computeIfAbsent(rs.getObject("owner", UUID.class), k -> new ArrayList<>())
              .add(
                  new ResourceDetail.Evidence(
                      rs.getObject("id", UUID.class),
                      URI.create(rs.getString("source_url")),
                      rs.getTimestamp("retrieved_at").toInstant(),
                      rs.getString("excerpt")));
        });
    return byOwner;
  }

  private void deleteAiFields(UUID resourceId) {
    deleteOrphanEvidence(
        jdbc.sql(
                """
                WITH gone AS (DELETE FROM field_assertion WHERE resource_id = :rid
                                AND origin IN ('AI_EXTRACTED', 'AI_ESTIMATED', 'SELF_DECLARED') RETURNING id)
                SELECT DISTINCT fae.evidence_id FROM field_assertion_evidence fae JOIN gone ON gone.id = fae.assertion_id
                """)
            .param("rid", resourceId)
            .query(UUID.class)
            .list());
  }

  private void deleteUnreviewedAiRelationships(UUID resourceId) {
    deleteOrphanEvidence(
        jdbc.sql(
                """
                WITH gone AS (
                    DELETE FROM relationship r WHERE r.from_resource_id = :rid
                      AND r.origin IN ('AI_EXTRACTED', 'SELF_DECLARED')
                      AND r.target_origin IS NULL
                      AND NOT EXISTS (SELECT 1 FROM review_item i
                                      WHERE i.relationship_id = r.id AND i.status IN ('ACCEPTED', 'REJECTED'))
                    RETURNING r.id)
                SELECT DISTINCT re.evidence_id FROM relationship_evidence re JOIN gone ON gone.id = re.relationship_id
                """)
            .param("rid", resourceId)
            .query(UUID.class)
            .list());
  }

  private void deleteOrphanEvidence(List<UUID> ids) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql(
            """
            DELETE FROM evidence e WHERE e.id IN (:ids)
              AND NOT EXISTS (SELECT 1 FROM field_assertion_evidence x WHERE x.evidence_id = e.id)
              AND NOT EXISTS (SELECT 1 FROM relationship_evidence x WHERE x.evidence_id = e.id)
              AND NOT EXISTS (SELECT 1 FROM summary_claim_evidence x WHERE x.evidence_id = e.id)
            """)
        .param("ids", ids)
        .update();
  }

  private int link(
      String joinTable, String fk, UUID owner, List<Citation> citations, Map<String, UUID> cache) {
    int linked = 0;
    for (Citation c : citations) {
      String key = c.sourceRecordId() + "|" + c.sourceUrl() + "|" + c.excerpt();
      UUID evidenceId = cache.computeIfAbsent(key, k -> storeEvidence(c));
      if (evidenceId == null) {
        continue;
      }
      linked++;
      jdbc.sql(
              "INSERT INTO %s (%s, evidence_id) VALUES (:o, :e) ON CONFLICT DO NOTHING"
                  .formatted(joinTable, fk))
          .param("o", owner)
          .param("e", evidenceId)
          .update();
    }
    return linked;
  }

  private @Nullable UUID storeEvidence(Citation c) {
    UUID id = UUID.randomUUID();
    int stored =
        jdbc.sql(
                """
                INSERT INTO evidence (id, source_record_id, source_url, retrieved_at, excerpt)
                SELECT :id, sr.id, coalesce(CAST(:url AS text), sr.source_url), coalesce(CAST(:retrieved AS timestamptz), sr.retrieved_at), :excerpt
                FROM source_record sr WHERE sr.id = :rec
                """)
            .param("id", id)
            .param("rec", c.sourceRecordId())
            .param("excerpt", c.excerpt())
            .param("url", c.sourceUrl() == null ? null : c.sourceUrl().toString())
            .param("retrieved", c.retrievedAt() == null ? null : Timestamp.from(c.retrievedAt()))
            .update();
    return stored == 1 ? id : null;
  }

  private boolean linkedEvidenceStands(
      String joinTable, String fk, UUID owner, List<Citation> citations, Map<String, UUID> cache) {
    return link(joinTable, fk, owner, citations, cache) > 0 || citations.isEmpty();
  }

  private void drop(String table, UUID id) {
    jdbc.sql("DELETE FROM %s WHERE id = :id".formatted(table)).param("id", id).update();
  }
}
