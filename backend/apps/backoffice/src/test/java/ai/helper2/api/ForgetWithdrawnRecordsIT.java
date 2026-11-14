package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.search.MergedResult;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.SourceContext;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceId;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ForgetWithdrawnRecordsIT extends ApiTestBase {

  @Autowired CatalogWriter writer;
  @Autowired JdbcClient jdbc;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String lists = "forget-lists-" + run;
  private final String other = "forget-other-" + run;
  private final Instant now = Instant.now();

  @BeforeEach
  void sources() {
    writer.registerSources(List.of(descriptor(lists), descriptor(other)));
  }

  private static SourceDescriptor descriptor(String id) {
    return new SourceDescriptor(
        id,
        id,
        "test",
        true,
        SourceMode.INDEXED,
        URI.create("https://helper2.ai/sources"),
        null,
        null,
        EnumSet.allOf(ResourceType.class),
        Duration.ofSeconds(1),
        Duration.ZERO);
  }

  private SourceHit listed(String source, String url, String license, Instant when) {
    return new SourceHit(
        source,
        source + ":" + url,
        URI.create(url),
        CanonicalUrl.of(url),
        null,
        "tool " + run,
        "An AI tool",
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.LIBRARY,
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        Set.of(),
        license,
        null,
        Set.of(),
        null,
        DateFact.unknown(),
        DateFact.unknown(),
        List.of(),
        when,
        0);
  }

  private void index(SourceHit hit) {
    List<MergedResult> merged =
        new ResultMerger()
            .merge(
                List.of(hit),
                Map.of(
                    hit.getSourceId(),
                    new SourceContext(descriptor(hit.getSourceId()), ServedFrom.LIVE)));
    writer.upsert(merged, DataOrigin.INDEXED, Set.of(hit.getSourceId()));
  }

  private UUID id(String url) {
    return ResourceId.of(CanonicalUrl.of(url)).getValue();
  }

  private int resources(String url) {
    return jdbc.sql("SELECT count(*) FROM resource WHERE id = :id")
        .param("id", id(url))
        .query(Integer.class)
        .single();
  }

  private Map<String, Object> facts(String url) {
    return jdbc.sql("SELECT license, type FROM resource WHERE id = :id")
        .param("id", id(url))
        .query()
        .singleRow();
  }

  @Test
  void forgettingWithdrawnRecordsRemovesEmptiedResourcesAndRebuildsTheOthers() {
    String renamed = "https://github.com/acme/old-name-" + run;
    String shared = "https://github.com/acme/shared-" + run;
    index(listed(lists, renamed, null, now));
    index(listed(lists, shared, "MIT", now));
    index(listed(other, shared, "Apache-2.0", now));
    assertThat(facts(shared).get("license")).as("disputed").isNull();

    CatalogWriter.Forgotten forgotten =
        writer.forgetRecords(
            lists, List.of((lists + ":" + renamed).toUpperCase(), lists + ":" + shared));

    assertThat(forgotten.records()).as("matched whatever the letter case").isEqualTo(2);
    assertThat(forgotten.resources()).isOne();
    assertThat(forgotten.keptResources()).containsExactly(id(shared));
    assertThat(resources(renamed)).isZero();
    assertThat(facts(shared)).containsEntry("license", "Apache-2.0");
  }

  @Test
  void forgettingWithdrawnRecordsKeepsACuratedResource() {
    String url = "https://github.com/acme/curated-" + run;
    index(listed(lists, url, null, now));
    jdbc.sql("UPDATE resource SET curated_fields = ARRAY['type'] WHERE id = :id")
        .param("id", id(url))
        .update();

    CatalogWriter.Forgotten forgotten = writer.forgetRecords(lists, List.of(lists + ":" + url));

    assertThat(forgotten.records()).isOne();
    assertThat(forgotten.resources()).isZero();
    assertThat(resources(url)).isOne();
  }

  @Test
  void aClaimThatLosesAllItsEvidenceGoesAndOneWithOtherEvidenceStays() {
    String url = "https://github.com/acme/claims-" + run;
    index(listed(lists, url, null, now));
    index(listed(other, url, null, now));
    UUID runId = enrichmentRun(id(url));
    UUID withdrawn = evidence(record(lists, url));
    UUID kept = evidence(record(other, url));
    UUID onlyWithdrawn = assertion(id(url), runId, "AI_EXTRACTED", withdrawn);
    UUID both = assertion(id(url), runId, "AI_EXTRACTED", withdrawn, kept);
    UUID curated = assertion(id(url), runId, "CURATED", withdrawn);
    UUID unsupported = assertion(id(url), runId, "AI_EXTRACTED");
    UUID relationship = relationship(id(url), runId, withdrawn);
    UUID lostClaim = summaryClaim(id(url), runId, 0, withdrawn);
    UUID keptClaim = summaryClaim(id(url), runId, 1, kept);

    writer.forgetRecords(lists, List.of(lists + ":" + url));

    assertThat(ids("field_assertion"))
        .contains(both, curated, unsupported)
        .doesNotContain(onlyWithdrawn);
    assertThat(ids("relationship")).doesNotContain(relationship);
    assertThat(ids("summary_claim")).contains(keptClaim).doesNotContain(lostClaim);
  }

  private UUID record(String source, String url) {
    return jdbc.sql("SELECT id FROM source_record WHERE source_id = :s AND external_id = :e")
        .param("s", source)
        .param("e", source + ":" + url)
        .query(UUID.class)
        .single();
  }

  private UUID enrichmentRun(UUID resource) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO enrichment_run (id, resource_id, provider, model, prompt_version, input_fingerprint,
                                        started_at, finished_at, outcome)
            VALUES (:id, :r, 'test', 'test', 'v', 'f', now(), now(), 'VALIDATED')
            """)
        .param("id", id)
        .param("r", resource)
        .update();
    return id;
  }

  private UUID evidence(UUID record) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO evidence (id, source_record_id, source_url, retrieved_at, excerpt)
            VALUES (:id, :sr, 'https://example.com', now(), 'quote')
            """)
        .param("id", id)
        .param("sr", record)
        .update();
    return id;
  }

  private UUID assertion(UUID resource, UUID runId, String origin, UUID... evidence) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO field_assertion (id, resource_id, field, value, origin, verification, enrichment_run_id)
            VALUES (:id, :r, 'license', 'MIT', :o, 'VERIFIED', :run)
            """)
        .param("id", id)
        .param("r", resource)
        .param("o", origin)
        .param("run", runId)
        .update();
    link("field_assertion_evidence", "assertion_id", id, evidence);
    return id;
  }

  private UUID relationship(UUID resource, UUID runId, UUID... evidence) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO relationship (id, from_resource_id, to_title, kind, verification, origin, enrichment_run_id)
            VALUES (:id, :r, :t, 'USES', 'VERIFIED', 'AI_EXTRACTED', :run)
            """)
        .param("id", id)
        .param("r", resource)
        .param("t", "target " + id)
        .param("run", runId)
        .update();
    link("relationship_evidence", "relationship_id", id, evidence);
    return id;
  }

  private UUID summaryClaim(UUID resource, UUID runId, int position, UUID... evidence) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO summary_claim (id, resource_id, enrichment_run_id, position, text)
            VALUES (:id, :r, :run, :p, 'A claim')
            """)
        .param("id", id)
        .param("r", resource)
        .param("run", runId)
        .param("p", position)
        .update();
    link("summary_claim_evidence", "claim_id", id, evidence);
    return id;
  }

  private void link(String table, String owner, UUID id, UUID... evidence) {
    for (UUID e : evidence) {
      jdbc.sql("INSERT INTO " + table + " (" + owner + ", evidence_id) VALUES (:o, :e)")
          .param("o", id)
          .param("e", e)
          .update();
    }
  }

  private List<UUID> ids(String table) {
    return jdbc.sql("SELECT id FROM " + table).query(UUID.class).list();
  }

  @Test
  void theLicenceGoesWhenTheLastIndexedRecordIsWithdrawn() {
    String url = "https://github.com/acme/last-indexed-" + run;
    index(listed(lists, url, "MIT", now));
    SourceHit seen = listed(other, url, null, now);
    writer.upsert(
        new ResultMerger()
            .merge(
                List.of(seen),
                Map.of(other, new SourceContext(descriptor(other), ServedFrom.LIVE))),
        DataOrigin.OBSERVED,
        Set.of(other));

    writer.forgetRecords(lists, List.of(lists + ":" + url));

    assertThat(resources(url)).isOne();
    assertThat(facts(url)).containsEntry("license", null).containsEntry("type", "LIBRARY");
  }

  @Test
  void anotherSourcesRecordIsNeverForgotten() {
    String url = "https://github.com/acme/kept-" + run;
    index(listed(other, url, null, now));

    assertThat(writer.forgetRecords(lists, List.of(other + ":" + url)).records()).isZero();
    assertThat(resources(url)).isOne();
  }
}
