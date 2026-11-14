package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.enrichment.EnrichmentCandidates;
import ai.helper2.catalog.enrichment.EnrichmentResults;
import ai.helper2.catalog.enrichment.StoredEnrichments;
import ai.helper2.scout.EnrichmentMaterial;
import ai.helper2.scout.GapKind;
import ai.helper2.scout.GapTarget;
import ai.helper2.scout.Material;
import ai.helper2.scout.QueuePlace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CatalogEnrichmentStoreTest {

  static final UUID ID = UUID.randomUUID();
  static final UUID RECORD = UUID.randomUUID();

  static final class FakeCandidates implements EnrichmentCandidates {
    @Nullable Candidate candidate;
    final List<String> calls = new ArrayList<>();
    @Nullable Collection<String> titlesAsked;

    @Override
    public List<UUID> pending(int limit, int minText, int maxFailed) {
      calls.add("pending " + limit + " " + minText + " " + maxFailed);
      return List.of(ID);
    }

    @Nullable Instant oldest;
    boolean oldestFails;

    @Override
    public @Nullable Instant oldestPending(int minText) {
      if (oldestFails) {
        throw new IllegalStateException("query timed out");
      }
      return oldest;
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

  static final class FakeStored implements StoredEnrichments {
    final List<UUID> requested = new ArrayList<>();

    @Override
    public List<UUID> judgedAfter(@Nullable UUID after, int limit) {
      return List.of();
    }

    @Override
    public Stored stored(UUID resourceId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int rejectFields(List<UUID> fieldIds) {
      return 0;
    }

    @Override
    public int dropClaims(List<UUID> claimIds) {
      return 0;
    }

    @Override
    public int closeItemsOfUnstatedValues(String by, String note) {
      return 0;
    }

    @Override
    public void requestRun(UUID resourceId) {
      requested.add(resourceId);
    }

    @Override
    public boolean done(String name) {
      return false;
    }

    @Override
    public void markDone(String name) {}
  }

  final FakeCandidates candidates = new FakeCandidates();
  final EnrichmentResults results = (run, checked) -> UUID.randomUUID();
  final FakeStored stored = new FakeStored();
  static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final CatalogEnrichmentStore store =
      new CatalogEnrichmentStore(
          candidates, results, stored, names -> Map.of(), meters, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aBatchReadTellsHowLongTheOldestNewItemHasWaited() {
    assertThat(meters.get("helper2.scout.enrichment.oldest_pending.age").gauge().value()).isNaN();
    candidates.oldest = NOW.minus(Duration.ofDays(3));

    store.pending(10);

    assertThat(meters.get("helper2.scout.enrichment.oldest_pending.age").gauge().value())
        .isEqualTo(Duration.ofDays(3).toSeconds());
  }

  @Test
  void anUnreadableOldestAgeKeepsTheBatchAndTheLastValue() {
    candidates.oldest = NOW.minus(Duration.ofDays(3));
    store.pending(10);
    candidates.oldestFails = true;

    assertThat(store.pending(10)).containsExactly(ID);
    assertThat(meters.get("helper2.scout.enrichment.oldest_pending.age").gauge().value())
        .isEqualTo(Duration.ofDays(3).toSeconds());
  }

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
  void theFieldsAnAdminSetAreFactsTheModelCannotChange() {
    EnrichmentCandidates.Candidate c = candidate("PRODUCT", true, "MIT");
    candidates.candidate =
        new EnrichmentCandidates.Candidate(
            c.title(),
            c.type(),
            c.openSource(),
            c.license(),
            c.sources(),
            Map.of("pricing", "PAID", "license", "Apache-2.0"));

    EnrichmentMaterial m = store.load(ID);

    assertThat(m.getInput().getSourceFacts())
        .containsEntry("pricing", "PAID")
        .containsEntry("license", "Apache-2.0")
        .containsEntry("openSource", "true");
    assertThat(m.getInput().getCuratedFields()).containsExactlyInAnyOrder("pricing", "license");
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
  void aLaterRunIsRequestedThroughTheCatalog() {
    store.requestRun(ID);

    assertThat(stored.requested).containsExactly(ID);
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
