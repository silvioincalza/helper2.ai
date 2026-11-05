package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.enrichment.EnrichmentCandidates;
import ai.helper2.catalog.enrichment.EnrichmentResults;
import ai.helper2.scout.EnrichmentMaterial;
import ai.helper2.scout.GapKind;
import ai.helper2.scout.GapTarget;
import ai.helper2.scout.Material;
import ai.helper2.scout.QueuePlace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The Scout's rules over the catalog's enrichment port: its bars, the source facts and the
 * fingerprint input.
 */
class CatalogEnrichmentStoreTest {

  static final UUID ID = UUID.randomUUID();
  static final UUID RECORD = UUID.randomUUID();

  /** Records what the store asks for; answers with what a test sets. */
  static final class FakeCandidates implements EnrichmentCandidates {
    @Nullable Candidate candidate;
    final List<String> calls = new ArrayList<>();
    @Nullable Collection<String> titlesAsked;

    @Override
    public List<UUID> pending(int limit, int minText, int maxFailed) {
      calls.add("pending " + limit + " " + minText + " " + maxFailed);
      return List.of(ID);
    }

    @Override
    public long pendingCount(int minText, int maxFailed) {
      calls.add("pendingCount " + minText + " " + maxFailed);
      return 7;
    }

    @Override
    public List<BacklogEntry> backlog(
        View view, @Nullable BacklogCursor after, int size, int minText, int maxFailed) {
      calls.add("backlog " + view + " " + size + " " + minText + " " + maxFailed);
      return List.of();
    }

    @Override
    public @Nullable Standing standing(UUID id, int minText, int maxFailed) {
      calls.add("standing " + minText + " " + maxFailed);
      return id.equals(ID) ? new Standing(true, false, false, true) : null;
    }

    @Override
    public int prioritisedCount(int minText, int maxFailed) {
      calls.add("prioritisedCount " + minText + " " + maxFailed);
      return 4;
    }

    @Override
    public List<UUID> withEnoughText(List<UUID> ids, int minText) {
      calls.add("withEnoughText " + minText);
      return ids;
    }

    @Override
    public @Nullable Candidate candidate(UUID id) {
      return candidate;
    }

    @Override
    public @Nullable String lastJudgedFingerprint(UUID id) {
      return "last";
    }

    @Override
    public Map<String, UUID> titled(Collection<String> lowerTitles, UUID excluding) {
      titlesAsked = lowerTitles;
      return Map.of();
    }
  }

  final FakeCandidates candidates = new FakeCandidates();
  final EnrichmentResults results = (run, checked) -> UUID.randomUUID();
  final CatalogEnrichmentStore store = new CatalogEnrichmentStore(candidates, results);

  private static EnrichmentCandidates.Candidate candidate(
      String type, @Nullable Boolean openSource, @Nullable String license) {
    return new EnrichmentCandidates.Candidate(
        "Tool",
        type,
        openSource,
        license,
        List.of(
            new EnrichmentCandidates.SourceText(
                RECORD,
                "https://example.org/tool",
                Instant.parse("2026-11-01T00:00:00Z"),
                "Tool\nDoes things.",
                "curated",
                "tool",
                null)));
  }

  @Test
  void theGapTargetComesFromTheSourceRecordsAndDoesNotChangeTheTextHash() {
    candidates.candidate = candidate("OTHER", null, null);
    EnrichmentMaterial plain = store.load(ID);
    assertThat(plain.getGapTarget()).isNull();

    candidates.candidate =
        new EnrichmentCandidates.Candidate(
            "Tool",
            "OTHER",
            null,
            null,
            List.of(
                new EnrichmentCandidates.SourceText(
                    RECORD,
                    "https://github.com/acme/tool",
                    Instant.parse("2026-11-01T00:00:00Z"),
                    "Tool\nDoes things.",
                    "github",
                    "acme/tool",
                    null)));
    EnrichmentMaterial github = store.load(ID);

    assertThat(github.getGapTarget())
        .isEqualTo(new GapTarget(GapKind.GITHUB_README, "acme", "tool", RECORD));
    assertThat(github.getTextHash()).isEqualTo(plain.getTextHash());
  }

  @Test
  void theScoutSetsTheTextBarAndTheRetryCap() {
    store.pending(5);
    long count = store.pendingCount();
    store.eligible(List.of(ID));
    store.queue(EnrichmentCandidates.View.SKIPPED, null, 20);
    store.place(ID);
    store.prioritisedCount();

    assertThat(count).isEqualTo(7);
    assertThat(candidates.calls)
        .containsExactly(
            "pending 5 40 3",
            "pendingCount 40 3",
            "withEnoughText 40",
            "backlog SKIPPED 20 40 3",
            "standing 40 3",
            "prioritisedCount 40 3");
  }

  @Test
  void aPlaceIsTheCatalogsStandingAndNoneForAnUnknownResource() {
    assertThat(store.place(ID)).isEqualTo(new QueuePlace(true, false, false, true));
    assertThat(store.place(UUID.randomUUID())).isNull();
  }

  @Test
  void noSourceRecordMeansNoMaterial() {
    assertThat(store.load(ID)).isNull();
  }

  @Test
  void sourceFactsAreTheLicenceTheTypeUnlessOtherAndOpenSource() {
    candidates.candidate = candidate("MCP_SERVER", true, "MIT");
    assertThat(store.load(ID).getInput().getSourceFacts())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("license", "MIT", "type", "MCP_SERVER", "openSource", "true"));

    candidates.candidate = candidate("OTHER", null, null);
    assertThat(store.load(ID).getInput().getSourceFacts()).isEmpty();
  }

  @Test
  void eachSourceRecordBecomesOneMaterialAndTheTextHashCoversThemAll() {
    candidates.candidate = candidate("OTHER", null, null);

    EnrichmentMaterial material = store.load(ID);

    assertThat(material.getInput().getResourceTitle()).isEqualTo("Tool");
    assertThat(material.getInput().getMaterials())
        .containsExactly(
            new Material(
                RECORD,
                java.net.URI.create("https://example.org/tool"),
                Instant.parse("2026-11-01T00:00:00Z"),
                "Tool\nDoes things."));
    // SHA-256 of "Tool\nDoes things.", first 16 bytes.
    assertThat(material.getTextHash()).isEqualTo(sha("Tool\nDoes things."));
  }

  @Test
  void onlyTitlesOfFourCharactersOrMoreAreLookedUpLowerCaseAndOnce() {
    assertThat(store.resourcesTitled(List.of("AI", " Go "), ID)).isEmpty();
    assertThat(candidates.titlesAsked).isNull();

    store.resourcesTitled(List.of(" Cursor ", "cursor", "AI", "Claude Desktop"), ID);

    assertThat(candidates.titlesAsked).containsExactly("cursor", "claude desktop");
  }

  @Test
  void theLastFingerprintComesFromTheCatalog() {
    assertThat(store.lastFingerprint(ID)).isEqualTo("last");
  }

  private static String sha(String s) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              0,
              16);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
