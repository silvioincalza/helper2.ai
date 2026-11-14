package ai.helper2.catalog;

import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class ReviewEvidenceFixture {

  public static final String SOURCE = "aa-review-evidence";
  public static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

  public final String url =
      CanonicalUrl.of("https://review-evidence.example/" + UUID.randomUUID()).getValue();

  public final UUID resource = ResourceId.of(CanonicalUrl.of(url)).getValue();
  public final UUID run = UUID.randomUUID();
  public final UUID validationItem = UUID.randomUUID();
  public final UUID relationshipItem = UUID.randomUUID();
  public final UUID reportItem = UUID.randomUUID();

  private final JdbcClient jdbc;
  private final UUID record = UUID.randomUUID();

  public ReviewEvidenceFixture(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public ReviewEvidenceFixture insert() {
    jdbc.sql(
            """
            INSERT INTO source (id, mode)
            VALUES (:id, 'CURATED')
            ON CONFLICT (id) DO NOTHING
            """)
        .param("id", SOURCE)
        .update();
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, 'Acme Tool', 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", resource)
        .param("url", url)
        .update();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                       type, retrieved_at, fingerprint)
            VALUES (:id, :source, :ext, :rid, :url, :url, 'Acme Tool', 'LIBRARY', :at, 'fp')
            """)
        .param("id", record)
        .param("source", SOURCE)
        .param("ext", record.toString())
        .param("rid", resource)
        .param("url", url)
        .param("at", Timestamp.from(T0))
        .update();
    jdbc.sql(
            """
            INSERT INTO enrichment_run (id, resource_id, provider, model, prompt_version, input_fingerprint,
                                        started_at, finished_at, outcome, validation_errors, extra_evidence)
            VALUES (:id, :rid, 'nebius', 'nvidia/nemotron-test', 'enrich-v9', 'fp', :at, :at, 'PARTIAL',
                    CAST('["pricing: the quotes disagree", "platforms: not a known platform"]' AS jsonb),
                    'github-readme:acme/tool')
            """)
        .param("id", run)
        .param("rid", resource)
        .param("at", Timestamp.from(T0))
        .update();
    UUID pricing = field("pricing", "FREE", "AI_EXTRACTED", "CONFLICTING");
    for (int i = 0; i < 7; i++) {
      quote("field_assertion_evidence", "assertion_id", pricing, "Pricing quote " + i, i, true);
    }
    UUID license = field("license", "MIT", "AI_EXTRACTED", "VERIFIED");
    quote("field_assertion_evidence", "assertion_id", license, "Released under MIT.", 0, false);
    field("audience", "BUILD_WITH_AI", "AI_ESTIMATED", "UNVERIFIED");
    UUID relationship = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO relationship (id, from_resource_id, to_title, kind, verification, origin, enrichment_run_id)
            VALUES (:id, :rid, 'LangChain', 'INTEGRATES_WITH', 'UNVERIFIED', 'SELF_DECLARED', :run)
            """)
        .param("id", relationship)
        .param("rid", resource)
        .param("run", run)
        .update();
    quote(
        "relationship_evidence", "relationship_id", relationship, "Works with LangChain.", 0, true);
    item(validationItem, "ENRICHMENT_VALIDATION", run, null);
    item(relationshipItem, "RELATIONSHIP_CANDIDATE", run, relationship);
    item(reportItem, "USER_REPORT", null, null);
    return this;
  }

  public UUID enrichmentItemConflictingOn(String field) {
    UUID otherRun = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO enrichment_run (id, resource_id, provider, model, prompt_version, input_fingerprint,
                                        started_at, finished_at, outcome)
            VALUES (:id, :rid, 'nebius', 'nvidia/nemotron-test', 'enrich-v9', 'fp', :at, :at, 'PARTIAL')
            """)
        .param("id", otherRun)
        .param("rid", resource)
        .param("at", Timestamp.from(T0))
        .update();
    field(otherRun, field, "x", "AI_EXTRACTED", "CONFLICTING");
    UUID item = UUID.randomUUID();
    item(item, "ENRICHMENT_VALIDATION", otherRun, null);
    return item;
  }

  public void delete() {
    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", resource).update();
    jdbc.sql("DELETE FROM source WHERE id = :id").param("id", SOURCE).update();
  }

  private UUID field(String field, String value, String origin, String verification) {
    return field(run, field, value, origin, verification);
  }

  private UUID field(UUID runId, String field, String value, String origin, String verification) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO field_assertion (id, resource_id, field, value, origin, verification, enrichment_run_id)
            VALUES (:id, :rid, :f, :v, :o, :ver, :run)
            """)
        .param("id", id)
        .param("rid", resource)
        .param("f", field)
        .param("v", value)
        .param("o", origin)
        .param("ver", verification)
        .param("run", runId)
        .update();
    return id;
  }

  private void quote(
      String link, String owner, UUID ownerId, String excerpt, int minutes, boolean fromRecord) {
    UUID evidence = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO evidence (id, source_record_id, source_url, retrieved_at, excerpt)
            VALUES (:id, :record, :url, :at, :excerpt)
            """)
        .param("id", evidence)
        .param("record", fromRecord ? record : null)
        .param("url", "https://acme.example/page/" + minutes)
        .param("at", Timestamp.from(T0.plusSeconds(60L * minutes)))
        .param("excerpt", excerpt)
        .update();
    jdbc.sql("INSERT INTO %s (%s, evidence_id) VALUES (:owner, :evidence)".formatted(link, owner))
        .param("owner", ownerId)
        .param("evidence", evidence)
        .update();
  }

  private void item(UUID id, String kind, UUID runId, UUID relationshipId) {
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, resource_id, enrichment_run_id, relationship_id, reason, note)
            VALUES (:id, :kind, :rid, :run, :rel, :reason, 'details')
            """)
        .param("id", id)
        .param("kind", kind)
        .param("rid", resource)
        .param("run", runId)
        .param("rel", relationshipId)
        .param("reason", kind + " of Acme Tool")
        .update();
  }
}
