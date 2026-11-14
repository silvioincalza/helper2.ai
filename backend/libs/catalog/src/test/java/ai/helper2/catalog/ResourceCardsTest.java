package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.search.DescribedSource;
import ai.helper2.search.FieldConflict;
import ai.helper2.search.MergedResult;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceRef;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.DatePrecision;
import ai.helper2.shared.Pricing;
import ai.helper2.shared.ResourceId;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ResourceCardsTest {

  private static final String URL = "https://github.com/acme/card-tool";
  private static final UUID ID = ResourceId.of(CanonicalUrl.of(URL)).getValue();
  private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

  private final ResourceCards cards =
      new ResourceCards(null, Stream.of("npm", "github").map(ResourceCardsTest::source).toList());

  private static DescribedSource source(String id) {
    SourceDescriptor descriptor =
        new SourceDescriptor(
            id,
            id,
            id,
            true,
            SourceMode.LIVE,
            URI.create("https://" + id + ".example"),
            null,
            null,
            EnumSet.allOf(ResourceType.class),
            Duration.ofSeconds(1),
            Duration.ZERO);
    return () -> descriptor;
  }

  private static final class Says {
    private final String source;
    private final DataOrigin origin;
    private ResourceType type = ResourceType.LIBRARY;
    private String licence;
    private Pricing pricing;
    private Boolean openSource;
    private Set<String> tags = Set.of();

    private Says(String source, DataOrigin origin) {
      this.source = source;
      this.origin = origin;
    }

    Says type(ResourceType stated) {
      type = stated;
      return this;
    }

    Says licence(String stated) {
      licence = stated;
      return this;
    }

    Says pricing(Pricing stated) {
      pricing = stated;
      return this;
    }

    Says openSource(boolean stated) {
      openSource = stated;
      return this;
    }

    Says tag(String stated) {
      tags = Set.of(stated);
      return this;
    }

    RecordRow record() {
      SourceHit hit =
          new SourceHit(
              source,
              source + ":" + URL,
              URI.create(URL),
              CanonicalUrl.of(URL),
              null,
              "Card tool",
              "A tool of this test",
              ValueOrigin.SOURCE_PROVIDED,
              type,
              Audience.BUILD_WITH_AI,
              null,
              Set.of(),
              tags,
              licence,
              pricing,
              Set.of(),
              openSource,
              DateFact.unknown(),
              DateFact.unknown(),
              List.of(),
              NOW,
              0);
      return new RecordRow(
          UUID.randomUUID(), source, source + ":" + URL, ID, URL, NOW, "f", origin, hit);
    }
  }

  private static Says indexed(String source) {
    return new Says(source, DataOrigin.INDEXED);
  }

  private static Says observed(String source) {
    return new Says(source, DataOrigin.OBSERVED);
  }

  private static Says ofNoRecordedOrigin(String source) {
    return new Says(source, null);
  }

  private static ResourceRow row(DataOrigin origin, ResourceType type) {
    return new ResourceRow(
        ID,
        URL,
        URL,
        "Card tool",
        "A tool of this test",
        ValueOrigin.SOURCE_PROVIDED,
        type,
        Audience.BUILD_WITH_AI,
        null,
        null,
        null,
        null,
        null,
        DatePrecision.UNKNOWN,
        null,
        null,
        null,
        null,
        null,
        NOW,
        origin,
        "NOT_RUN",
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        List.of(),
        Set.of());
  }

  private MergedResult card(DataOrigin origin, Says... records) {
    return cards
        .card(row(origin, ResourceType.LIBRARY), Stream.of(records).map(Says::record).toList())
        .orElseThrow();
  }

  private static List<String> conflictingFields(MergedResult card) {
    return card.getConflicts().stream().map(FieldConflict::getField).toList();
  }

  @Test
  void aRecordOnlyASearchSawDoesNotBlankTheLicenceOfAnIndexedResource() {
    MergedResult card =
        card(
            DataOrigin.INDEXED,
            indexed("npm").licence("MIT"),
            observed("github").licence("Apache-2.0"));

    assertThat(card.getLicense()).isEqualTo("MIT");
    assertThat(conflictingFields(card)).doesNotContain("license");
  }

  @Test
  void aRecordOnlyASearchSawDoesNotBlankThePricingOrTheOpenSourceFlagOfAnIndexedResource() {
    MergedResult card =
        card(
            DataOrigin.INDEXED,
            indexed("npm").pricing(Pricing.FREE).openSource(true),
            observed("github").pricing(Pricing.PAID).openSource(false));

    assertThat(card.getPricing()).isEqualTo(Pricing.FREE);
    assertThat(card.getOpenSource()).isTrue();
    assertThat(conflictingFields(card)).doesNotContain("pricing", "openSource");
  }

  @Test
  void aRecordOnlyASearchSawDoesNotChangeTheTypeOfAnIndexedResource() {
    MergedResult card =
        card(
            DataOrigin.INDEXED,
            indexed("npm").type(ResourceType.LIBRARY),
            observed("github").type(ResourceType.MCP_SERVER));

    assertThat(card.getType()).isEqualTo(ResourceType.LIBRARY);
  }

  @Test
  void aRecordOnlyASearchSawIsStillASourceOfTheCardAndGivesItsTags() {
    MergedResult card =
        card(
            DataOrigin.INDEXED,
            indexed("npm").licence("MIT"),
            observed("github").licence("Apache-2.0").tag("seen-by-a-search"));

    assertThat(card.getSources().stream().map(SourceRef::getSourceId))
        .containsExactlyInAnyOrder("npm", "github");
    assertThat(card.getTags()).contains("seen-by-a-search");
  }

  @Test
  void indexedRecordsThatDisagreeStillLeaveTheLicenceOpen() {
    MergedResult card =
        card(
            DataOrigin.INDEXED,
            indexed("npm").licence("MIT"),
            indexed("github").licence("Apache-2.0"));

    assertThat(card.getLicense()).isNull();
    assertThat(conflictingFields(card)).contains("license");
  }

  @Test
  void aResourceOnlySearchesSawShowsWhatItsRecordsState() {
    MergedResult card =
        card(
            DataOrigin.OBSERVED,
            observed("npm").licence("MIT").pricing(Pricing.FREE).openSource(true),
            observed("github").type(ResourceType.MCP_SERVER));

    assertThat(card.getLicense()).isEqualTo("MIT");
    assertThat(card.getPricing()).isEqualTo(Pricing.FREE);
    assertThat(card.getOpenSource()).isTrue();
    assertThat(card.getType()).isEqualTo(ResourceType.MCP_SERVER);
  }

  @Test
  void aRecordOfNoRecordedOriginCountsAsOneOfTheResourcesOwnOrigin() {
    MergedResult card =
        card(
            DataOrigin.INDEXED,
            indexed("npm").licence("MIT"),
            ofNoRecordedOrigin("github").licence("Apache-2.0"));

    assertThat(card.getLicense()).isNull();
  }

  @Test
  void anIndexedResourceWhoseRecordsOnlySearchesSawStatesNoFactAndKeepsTheTypeOfItsRow() {
    MergedResult card =
        card(
            DataOrigin.INDEXED,
            observed("github")
                .type(ResourceType.MCP_SERVER)
                .licence("MIT")
                .pricing(Pricing.FREE)
                .openSource(true));

    assertThat(card.getType()).isEqualTo(ResourceType.LIBRARY);
    assertThat(card.getLicense()).isNull();
    assertThat(card.getPricing()).isNull();
    assertThat(card.getOpenSource()).isNull();
  }

  @Test
  void aCuratedResourceCountsEveryRecordAsItsRowIsNotRebuiltFromThem() {
    MergedResult card =
        card(DataOrigin.CURATED, indexed("npm").licence("MIT"), observed("github").licence("MIT"));

    assertThat(card.getLicense()).isEqualTo("MIT");
  }
}
