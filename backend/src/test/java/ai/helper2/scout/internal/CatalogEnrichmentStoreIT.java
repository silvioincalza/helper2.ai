package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.enrichment.StoredEnrichments;
import ai.helper2.catalog.internal.JdbcEnrichmentResults;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.scout.CheckedCitation;
import ai.helper2.scout.CheckedClaim;
import ai.helper2.scout.CheckedField;
import ai.helper2.scout.CheckedRelationship;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentMaterial;
import ai.helper2.scout.EnrichmentModel;
import ai.helper2.scout.EnrichmentOutcome;
import ai.helper2.scout.EnrichmentProposal;
import ai.helper2.scout.GapKind;
import ai.helper2.scout.Material;
import ai.helper2.scout.ValidationResult;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import ai.helper2.testing.StatementCounting;
import ai.helper2.testing.StatementCounting.Counted;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What one saved enrichment run leaves in PostgreSQL: fingerprints, relationships, evidence and
 * review items, and how the detail page reads them back (with the number of statements that takes).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StatementCounting.class})
class CatalogEnrichmentStoreIT {

  static final EnrichmentModel MODEL =
      new EnrichmentModel() {
        @Override
        public @NotNull String getProvider() {
          return "fake";
        }

        @Override
        public @NotNull String getModel() {
          return "m";
        }

        @Override
        public @NotNull String getPromptVersion() {
          return "enrich-v3";
        }

        @Override
        public @NotNull EnrichmentProposal propose(@NotNull EnrichmentInput input) {
          throw new UnsupportedOperationException();
        }
      };

  @Autowired CatalogEnrichmentStore store;

  @Autowired ResolveReviewItem resolve;

  @Autowired JdbcClient jdbc;

  @Autowired JdbcEnrichmentResults results;

  UUID resource;
  UUID record;

  /** A second record of the same resource, retrieved a day earlier: its evidence comes first. */
  UUID older;

  /** When the README was read: before both records, so its evidence comes first. */
  final Instant readmeReadAt =
      Instant.now()
          .minus(java.time.Duration.ofDays(2))
          .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

  @BeforeEach
  void resourceWithOneSourceRecord() {
    resource = UUID.randomUUID();
    record = UUID.randomUUID();
    String url = "https://enrichment-store.example/" + resource;
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, 'Store test', 'SOURCE_PROVIDED', 'MCP_SERVER', 'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", resource)
        .param("url", url)
        .update();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                       description, type, retrieved_at, fingerprint)
            VALUES (:id, 'curated', :id::text, :rid, :url, :url, 'Store test',
                    'Works with Claude Desktop and with Cursor.', 'MCP_SERVER', now(), 'fp')
            """)
        .param("id", record)
        .param("rid", resource)
        .param("url", url)
        .update();
    older = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                       description, type, retrieved_at, fingerprint)
            VALUES (:id, 'curated', :id::text, :rid, :url, :url, 'Store test', 'Older text.', 'MCP_SERVER',
                    now() - interval '1 day', 'fp')
            """)
        .param("id", older)
        .param("rid", resource)
        .param("url", url + "/older")
        .update();
  }

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM review_item WHERE resource_id = :id").param("id", resource).update();
    jdbc.sql("DELETE FROM relationship WHERE from_resource_id = :id")
        .param("id", resource)
        .update();
    jdbc.sql("DELETE FROM summary_claim WHERE resource_id = :id").param("id", resource).update();
    jdbc.sql("DELETE FROM field_assertion WHERE resource_id = :id").param("id", resource).update();
    jdbc.sql("DELETE FROM enrichment_run WHERE resource_id = :id").param("id", resource).update();
    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", resource).update();
    jdbc.sql("DELETE FROM source WHERE id = 'aa-golden'").update();
  }

  private void save(String fingerprint, EnrichmentOutcome outcome, ValidationResult result) {
    save(fingerprint, outcome, result, Instant.now());
  }

  private void save(
      String fingerprint, EnrichmentOutcome outcome, ValidationResult result, Instant startedAt) {
    store.save(
        resource,
        MODEL,
        fingerprint,
        startedAt,
        result,
        outcome,
        result == null ? "boom" : null,
        null);
  }

  private CheckedRelationship relationship(String target, Verification verification) {
    return new CheckedRelationship(
        "COMPATIBLE_WITH",
        target,
        null,
        verification,
        List.of(new CheckedCitation(record, "Works with " + target)));
  }

  private ValidationResult partial(List<CheckedRelationship> relationships, String... errors) {
    return new ValidationResult(
        EnrichmentOutcome.PARTIAL, List.of(), relationships, List.of(), List.of(errors));
  }

  private List<String> relationships() {
    return jdbc.sql(
            "SELECT to_title || ':' || verification FROM relationship WHERE from_resource_id = :id ORDER BY to_title")
        .param("id", resource)
        .query(String.class)
        .list();
  }

  private List<String> reviewStatuses(ReviewQueue.Kind kind) {
    return jdbc.sql(
            "SELECT status FROM review_item WHERE resource_id = :id AND kind = :k ORDER BY created_at, status")
        .param("id", resource)
        .param("k", kind.name())
        .query(String.class)
        .list();
  }

  /** The overview's backlog count follows the same rule as the backlog the Scout takes. */
  @Test
  void theBacklogCountIsTheBacklog() {
    List<UUID> backlog = store.pending(Integer.MAX_VALUE);
    assertThat(backlog).contains(resource);
    assertThat(store.pendingCount()).isEqualTo(backlog.size());
  }

  /**
   * ADR 0056: an AI-estimated judgement, a self-declared fact, a self-declared relationship and a
   * self-declared claim keep their origin through the database to the detail page.
   */
  @Test
  void estimatedAndSelfDeclaredValuesKeepTheirOrigin() {
    URI page = URI.create("https://enrichment-store.example/pricing");
    CheckedCitation fromPage =
        new CheckedCitation(
            UUID.randomUUID(), "Pro costs $12 per month", record, page, Instant.now());
    ValidationResult result =
        new ValidationResult(
            EnrichmentOutcome.PARTIAL,
            List.of(
                new CheckedField(
                    "pricing",
                    "PAID",
                    Verification.VERIFIED,
                    List.of(fromPage),
                    ValueOrigin.SELF_DECLARED),
                new CheckedField(
                    "audience",
                    "USE_AI",
                    Verification.UNVERIFIED,
                    List.of(),
                    ValueOrigin.AI_ESTIMATED)),
            List.of(
                new CheckedRelationship(
                    "INTEGRATES_WITH",
                    "Slack",
                    null,
                    Verification.UNVERIFIED,
                    List.of(
                        new CheckedCitation(
                            UUID.randomUUID(), "Works with Slack", record, page, Instant.now())),
                    ValueOrigin.SELF_DECLARED)),
            List.of(new CheckedClaim("Pro costs $12 per month.", List.of(fromPage), true)),
            List.of());

    save("fp-ladder", EnrichmentOutcome.PARTIAL, result);

    assertThat(results.assertions(resource))
        .extracting(a -> a.field() + ":" + a.origin() + ":" + a.verification())
        .containsExactly("audience:AI_ESTIMATED:UNVERIFIED", "pricing:SELF_DECLARED:VERIFIED");
    assertThat(results.relationships(resource))
        .extracting(r -> r.targetTitle() + ":" + r.origin())
        .containsExactly("Slack:SELF_DECLARED");
    // A relationship only the vendor names waits for a person.
    assertThat(reviewStatuses(ReviewQueue.Kind.RELATIONSHIP_CANDIDATE)).containsExactly("OPEN");
    assertThat(results.summary(resource).claims().getFirst().selfDeclared()).isTrue();

    // The next run replaces them all, estimated and self-declared values included.
    save(
        "fp-next",
        EnrichmentOutcome.VALIDATED,
        new ValidationResult(
            EnrichmentOutcome.VALIDATED, List.of(), List.of(), List.of(), List.of()));
    assertThat(results.assertions(resource)).isEmpty();
    assertThat(results.relationships(resource)).isEmpty();
  }

  /**
   * ADR 0056: a re-check by code reads the stored answer, rejects a field, drops a claim and puts
   * the resource back in the backlog past its unchanged fingerprint.
   */
  @Test
  void aRecheckRejectsFieldsDropsClaimsAndRequestsARun() {
    CheckedCitation cite = new CheckedCitation(record, "Works with Claude Desktop");
    save(
        "fp-recheck",
        EnrichmentOutcome.VALIDATED,
        new ValidationResult(
            EnrichmentOutcome.VALIDATED,
            List.of(new CheckedField("pricing", "FREE", Verification.VERIFIED, List.of(cite))),
            List.of(),
            List.of(new CheckedClaim("The best MCP server.", List.of(cite))),
            List.of()));
    assertThat(store.lastFingerprint(resource)).isEqualTo("fp-recheck");

    StoredEnrichments.Stored stored = results.stored(resource);
    assertThat(stored.promptVersion()).isEqualTo("enrich-v3");
    assertThat(stored.fields())
        .singleElement()
        .satisfies(f -> assertThat(f.excerpts()).containsExactly("Works with Claude Desktop"));
    assertThat(stored.claims())
        .singleElement()
        .extracting(StoredEnrichments.StoredClaim::text)
        .isEqualTo("The best MCP server.");

    assertThat(results.rejectFields(List.of(stored.fields().getFirst().id()))).isEqualTo(1);
    assertThat(results.rejectFields(List.of(stored.fields().getFirst().id()))).isZero();
    assertThat(results.dropClaims(List.of(stored.claims().getFirst().id()))).isEqualTo(1);
    assertThat(results.summary(resource)).isNull();
    assertThat(results.assertions(resource))
        .singleElement()
        .extracting(ResourceDetail.Assertion::verification)
        .isEqualTo(Verification.REJECTED);

    results.requestRun(resource);
    assertThat(store.lastFingerprint(resource))
        .as("an unchanged fingerprint no longer skips")
        .isNull();
    assertThat(store.pending(Integer.MAX_VALUE)).contains(resource);

    // The run after the request counts again: the next unchanged attempt is UNCHANGED. The request
    // is stamped by the database's clock (now()) and the run by this JVM's; the Postgres
    // container's
    // clock can run a few milliseconds ahead of the host's, which hid a run started "at once"
    // (5 Nov 2026), so this run starts clearly later.
    save(
        "fp-after",
        EnrichmentOutcome.VALIDATED,
        new ValidationResult(
            EnrichmentOutcome.VALIDATED, List.of(), List.of(), List.of(), List.of()),
        Instant.now().plusSeconds(60));
    assertThat(store.lastFingerprint(resource)).isEqualTo("fp-after");
  }

  @Test
  void aMaintenanceTaskIsClaimedOnce() {
    String name = "test-" + UUID.randomUUID();
    try {
      assertThat(results.claimOnce(name)).isTrue();
      assertThat(results.claimOnce(name)).isFalse();
    } finally {
      jdbc.sql("DELETE FROM maintenance_task WHERE name = :n").param("n", name).update();
    }
  }

  /** ADR 0056: a product a person accepted from the review queue is enriched first. */
  @Test
  void anAcceptedCandidateComesFirstInTheBacklog() {
    String url =
        jdbc.sql("SELECT canonical_url FROM resource WHERE id = :id")
            .param("id", resource)
            .query(String.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, status, candidate_url, reason, resolved_at)
            VALUES (:id, 'RESOURCE_CANDIDATE', 'ACCEPTED', :url, 'test', now())
            """)
        .param("id", UUID.randomUUID())
        .param("url", url)
        .update();
    try {
      assertThat(store.pending(1)).containsExactly(resource);
    } finally {
      jdbc.sql("DELETE FROM review_item WHERE candidate_url = :url").param("url", url).update();
    }
  }

  @Test
  void readmeEvidenceKeepsItsOwnPlaceAndBelongsToTheRecord() {
    URI readme = URI.create("https://github.com/acme/tool#readme");
    Instant read = Instant.parse("2026-11-04T10:00:00Z");
    CheckedCitation citation =
        new CheckedCitation(
            UUID.randomUUID(), "released under the MIT License", record, readme, read);
    ValidationResult result =
        new ValidationResult(
            EnrichmentOutcome.VALIDATED,
            List.of(new CheckedField("license", "MIT", Verification.VERIFIED, List.of(citation))),
            List.of(),
            List.of(),
            List.of());

    store.save(
        resource,
        MODEL,
        "fp-1",
        Instant.now(),
        result,
        EnrichmentOutcome.VALIDATED,
        null,
        "github-readme:acme/tool");

    assertThat(
            jdbc.sql(
                    """
                    SELECT e.source_url || '|' || e.source_record_id || '|' || e.retrieved_at FROM evidence e
                    JOIN field_assertion_evidence x ON x.evidence_id = e.id JOIN field_assertion f ON f.id = x.assertion_id
                    WHERE f.resource_id = :id
                    """)
                .param("id", resource)
                .query(String.class)
                .single())
        .startsWith(readme + "|" + record + "|2026-11-04");
    assertThat(
            jdbc.sql("SELECT extra_evidence FROM enrichment_run WHERE resource_id = :id")
                .param("id", resource)
                .query(String.class)
                .single())
        .isEqualTo("github-readme:acme/tool");
  }

  @Test
  void aGitHubRecordGivesTheGapTarget() {
    assertThat(store.load(resource).getGapTarget()).isNull();
    UUID github = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                       description, type, retrieved_at, fingerprint)
            VALUES (:id, 'github', :id::text, :rid, 'https://github.com/acme/tool', 'https://github.com/acme/tool',
                    'acme/tool', 'A tool.', 'MCP_SERVER', now(), 'fp')
            """)
        .param("id", github)
        .param("rid", resource)
        .update();

    var target = store.load(resource).getGapTarget();

    assertThat(target).isNotNull();
    assertThat(target.getKind()).isEqualTo(GapKind.GITHUB_README);
    assertThat(target.getAnchorRecordId()).isEqualTo(github);
  }

  @Test
  void theLastJudgedRunGivesTheFingerprintRunsWithoutAJudgedAnswerArePassedOver() {
    assertThat(store.lastFingerprint(resource)).isNull();

    save(
        "rejected-input",
        EnrichmentOutcome.REJECTED,
        new ValidationResult(
            EnrichmentOutcome.REJECTED, List.of(), List.of(), List.of(), List.of("nothing cited")));
    assertThat(store.lastFingerprint(resource)).isEqualTo("rejected-input");

    save("failed-input", EnrichmentOutcome.FAILED, null);
    save("unavailable-input", EnrichmentOutcome.LLM_UNAVAILABLE, null);
    assertThat(store.lastFingerprint(resource)).isEqualTo("rejected-input");
  }

  @Test
  void aNewRunReplacesUnreviewedAiRelationshipsAndKeepsReviewedOnes() {
    save(
        "run-1",
        EnrichmentOutcome.PARTIAL,
        partial(
            List.of(
                relationship("Claude Desktop", Verification.UNVERIFIED),
                relationship("Cursor", Verification.UNVERIFIED))));
    UUID accepted =
        jdbc.sql(
                """
                SELECT i.id FROM review_item i JOIN relationship r ON r.id = i.relationship_id
                WHERE r.from_resource_id = :id AND r.to_title = 'Cursor'
                """)
            .param("id", resource)
            .query(UUID.class)
            .single();
    resolve.resolve(accepted, ReviewQueue.Status.ACCEPTED, "admin", null);

    // The second run no longer proposes Claude Desktop, and proposes Cursor again.
    save(
        "run-2",
        EnrichmentOutcome.PARTIAL,
        partial(List.of(relationship("Cursor", Verification.UNVERIFIED))));

    assertThat(relationships()).containsExactly("Cursor:VERIFIED");
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM evidence WHERE source_record_id = :rec AND excerpt = 'Works with Claude Desktop'")
                .param("rec", record)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM evidence WHERE source_record_id = :rec AND excerpt = 'Works with Cursor'")
                .param("rec", record)
                .query(Long.class)
                .single())
        .isOne();
  }

  @Test
  void aNewRunSupersedesTheOpenItemsOfEarlierRuns() {
    save(
        "run-1",
        EnrichmentOutcome.PARTIAL,
        partial(List.of(relationship("Claude Desktop", Verification.UNVERIFIED)), "first issue"));
    save(
        "run-2",
        EnrichmentOutcome.PARTIAL,
        partial(List.of(relationship("Claude Desktop", Verification.UNVERIFIED)), "second issue"));

    assertThat(reviewStatuses(ReviewQueue.Kind.ENRICHMENT_VALIDATION))
        .containsExactly("SUPERSEDED", "OPEN");
    assertThat(reviewStatuses(ReviewQueue.Kind.RELATIONSHIP_CANDIDATE))
        .containsExactly("SUPERSEDED", "OPEN");
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM review_item WHERE resource_id = :id AND status = 'SUPERSEDED' AND resolved_at IS NOT NULL
                    """)
                .param("id", resource)
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void aRunWithoutModelOutputLeavesTheLastOutputAndItsItemsAlone() {
    save(
        "run-1",
        EnrichmentOutcome.PARTIAL,
        partial(List.of(relationship("Claude Desktop", Verification.UNVERIFIED)), "issue"));

    save("run-1", EnrichmentOutcome.LLM_UNAVAILABLE, null);

    assertThat(relationships()).containsExactly("Claude Desktop:UNVERIFIED");
    assertThat(reviewStatuses(ReviewQueue.Kind.ENRICHMENT_VALIDATION)).containsExactly("OPEN");
  }

  private static List<CheckedCitation> cites(UUID rec, String... excerpts) {
    return java.util.Arrays.stream(excerpts).map(e -> new CheckedCitation(rec, e)).toList();
  }

  private static <T> List<T> concat(List<T> a, List<T> b) {
    return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
  }

  static final URI README = URI.create("https://github.com/acme/tool#readme");

  /**
   * Three fields, three relationships and three summary claims, with evidence shared between owners
   * and kinds, evidence from two records retrieved on different days, and README evidence (ADR
   * 0043) read two days ago.
   */
  private void saveFullRun() {
    List<CheckedCitation> shared = cites(record, "Works with Claude Desktop");
    CheckedCitation fromReadme =
        new CheckedCitation(UUID.randomUUID(), "Free for everyone", record, README, readmeReadAt);
    ValidationResult result =
        new ValidationResult(
            EnrichmentOutcome.VALIDATED,
            List.of(
                new CheckedField(
                    "license",
                    "MIT",
                    Verification.VERIFIED,
                    concat(cites(record, "MIT"), cites(older, "MIT old"))),
                new CheckedField(
                    "pricing",
                    "FREE",
                    Verification.VERIFIED,
                    concat(cites(record, "Free"), List.of(fromReadme))),
                new CheckedField("techLevel", "BEGINNER", Verification.UNVERIFIED, shared)),
            List.of(
                relationship("Claude Desktop", Verification.UNVERIFIED),
                new CheckedRelationship(
                    "COMPATIBLE_WITH",
                    "Cursor",
                    null,
                    Verification.UNVERIFIED,
                    concat(shared, cites(older, "Works with Cursor"))),
                new CheckedRelationship(
                    "COMPATIBLE_WITH", "Zed", null, Verification.UNVERIFIED, List.of())),
            List.of(
                new CheckedClaim("Works with Claude Desktop.", shared),
                new CheckedClaim(
                    "Free and MIT.", concat(cites(record, "Free"), cites(older, "MIT old"))),
                new CheckedClaim("Uncited claim.", List.of())),
            List.of());
    save("full", EnrichmentOutcome.VALIDATED, result);
  }

  private record Detail(
      List<ResourceDetail.Assertion> assertions,
      List<ResourceDetail.Relationship> relationships,
      ResourceDetail.Summary summary) {}

  private Counted<Detail> readDetail(UUID id) {
    return StatementCounting.count(
        () -> new Detail(results.assertions(id), results.relationships(id), results.summary(id)));
  }

  private static List<String> excerpts(List<ResourceDetail.Evidence> evidence) {
    return evidence.stream().map(ResourceDetail.Evidence::excerpt).toList();
  }

  @Test
  void theDetailReadsEachOwnersEvidenceOldestFirstWithEvidenceSharedBetweenOwners() {
    saveFullRun();

    Detail detail = readDetail(resource).result();

    assertThat(detail.assertions())
        .extracting(ResourceDetail.Assertion::field)
        .containsExactly("license", "pricing", "techLevel");
    assertThat(excerpts(detail.assertions().get(0).evidence())).containsExactly("MIT old", "MIT");
    assertThat(excerpts(detail.assertions().get(1).evidence()))
        .containsExactly("Free for everyone", "Free");
    // README evidence keeps its own place and read time on the detail page (ADR 0043), in the same
    // batched query.
    ResourceDetail.Evidence readme = detail.assertions().get(1).evidence().getFirst();
    assertThat(readme.sourceUrl()).isEqualTo(README);
    assertThat(readme.retrievedAt()).isEqualTo(readmeReadAt);
    assertThat(detail.assertions().get(1).evidence().get(1).sourceUrl()).isNotEqualTo(README);
    assertThat(excerpts(detail.assertions().get(2).evidence()))
        .containsExactly("Works with Claude Desktop");
    assertThat(detail.relationships())
        .extracting(ResourceDetail.Relationship::targetTitle)
        .containsExactly("Claude Desktop", "Cursor", "Zed");
    assertThat(excerpts(detail.relationships().get(0).evidence()))
        .containsExactly("Works with Claude Desktop");
    assertThat(excerpts(detail.relationships().get(1).evidence()))
        .containsExactly("Works with Cursor", "Works with Claude Desktop");
    assertThat(detail.relationships().get(2).evidence()).isEmpty();
    assertThat(detail.summary().claims())
        .extracting(ResourceDetail.SummaryClaim::text)
        .containsExactly("Works with Claude Desktop.", "Free and MIT.", "Uncited claim.");
    assertThat(excerpts(detail.summary().claims().get(1).evidence()))
        .containsExactly("MIT old", "Free");
    assertThat(detail.summary().claims().get(2).evidence()).isEmpty();
    // One evidence row per (record, excerpt): the shared excerpt is the same row everywhere.
    assertThat(detail.relationships().get(0).evidence().getFirst().id())
        .isEqualTo(detail.assertions().get(2).evidence().getFirst().id())
        .isEqualTo(detail.summary().claims().getFirst().evidence().getFirst().id());
  }

  /**
   * Owners first, then one evidence query per kind: 7 statements however many fields, relationships
   * and claims, and whether the evidence comes from a source record or a README. Baseline before
   * batching: 4 + A + R + C, here 13.
   */
  @Test
  void theDetailTakesSevenStatementsWhateverTheNumberOfOwners() {
    saveFullRun();

    assertThat(readDetail(resource).statements()).isEqualTo(7);
  }

  @Test
  void aResourceWithoutEnrichmentReadsEmptyWithoutEvidenceQueries() {
    Counted<Detail> read = readDetail(resource);

    assertThat(read.result().assertions()).isEmpty();
    assertThat(read.result().relationships()).isEmpty();
    assertThat(read.result().summary()).isNull();
    assertThat(read.statements()).as("owners only, nothing to load evidence for").isEqualTo(3);
  }

  /**
   * The fingerprint decides whether a resource is enriched again. Its input is each source record
   * as {@code title + "\n" + description} (no description: an empty one), in source id order. If
   * this value changes, the Scout re-enriches the whole catalog.
   */
  @Test
  void theMaterialAndItsFingerprintStayByteIdentical() {
    jdbc.sql("DELETE FROM source_record WHERE id = :id").param("id", older).update();
    jdbc.sql(
            """
            INSERT INTO source (id, name, mode, homepage, attribution)
            VALUES ('aa-golden', 'Golden test', 'CURATED', 'https://helper2.ai/sources', 'Test')
            ON CONFLICT (id) DO NOTHING
            """)
        .update();
    UUID second = UUID.randomUUID();
    String url = "https://enrichment-store.example/second/" + resource;
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                       description, type, retrieved_at, fingerprint)
            VALUES (:id, 'aa-golden', :id::text, :rid, :url, :url, 'Store test — ünïcode', NULL, 'MCP_SERVER', now(), 'fp')
            """)
        .param("id", second)
        .param("rid", resource)
        .param("url", url)
        .update();
    jdbc.sql(
            "UPDATE source_record SET description = 'Works with Claude Desktop and with Cursor.\nSecond line.' WHERE id = :id")
        .param("id", record)
        .update();

    EnrichmentMaterial material = store.load(resource);

    assertThat(material.getInput().getMaterials())
        .extracting(Material::getText)
        .containsExactly(
            "Store test — ünïcode\n",
            "Store test\nWorks with Claude Desktop and with Cursor.\nSecond line.");
    assertThat(material.getTextHash()).isEqualTo("f0687fef5a659106ccefb5499837d3b0");
    assertThat(material.fingerprintFor(MODEL)).isEqualTo("88bb3ba375c917c44707219947bc8154");
  }
}
