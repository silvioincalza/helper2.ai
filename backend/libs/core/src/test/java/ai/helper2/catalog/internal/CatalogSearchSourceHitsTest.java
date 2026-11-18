package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.ResourceRow;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.search.Vitality;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.DatePrecision;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.Pricing;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.SourcedValue;
import ai.helper2.shared.TechLevel;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogSearchSourceHitsTest {

  static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
  static final UUID SUMMIT = UUID.fromString("7b0e3c52-6a6f-4a6e-9d0c-3f1f6a1c2d01");
  static final UUID UNDATED = UUID.fromString("7b0e3c52-6a6f-4a6e-9d0c-3f1f6a1c2d02");

  static final SourceDescriptor INDEX =
      new SourceDescriptor(
          "helper2-index",
          "helper2.ai catalog",
          "Catalog",
          false,
          SourceMode.INDEXED,
          URI.create("https://helper2.ai/sources"),
          null,
          null,
          EnumSet.allOf(ResourceType.class),
          Duration.ofSeconds(1),
          Duration.ZERO);

  static ResourceRow summit() {
    return new ResourceRow(
        SUMMIT,
        "https://summit.example/2027",
        "https://www.summit.example/2027?ref=home",
        "Agent Summit 2027",
        "Two days about AI agents.",
        ValueOrigin.AI_SUMMARY,
        ResourceType.EVENT,
        Audience.BOTH,
        TechLevel.ADVANCED,
        "CC-BY-4.0",
        Pricing.PAID,
        false,
        Instant.parse("2026-05-01T00:00:00Z"),
        DatePrecision.DAY,
        "confs-tech",
        Instant.parse("2026-09-01T08:30:00Z"),
        "github",
        Instant.parse("2027-03-12T00:00:00Z"),
        Instant.parse("2027-03-14T00:00:00Z"),
        Instant.parse("2026-04-01T00:00:00Z"),
        DataOrigin.INDEXED,
        "DONE",
        Set.of("conference"),
        Set.of("agents"),
        Set.of("web"),
        Set.of("events"),
        List.of(new PopularityMetric(PopularityKind.GITHUB_STARS, 42, "github", NOW)),
        Set.of("title"));
  }

  static ResourceRow undated() {
    return new ResourceRow(
        UNDATED,
        "https://tool.example",
        "https://tool.example",
        "Tool",
        null,
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.PRODUCT,
        Audience.USE_AI,
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
        null,
        DataOrigin.OBSERVED,
        null,
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        List.of(),
        Set.of());
  }

  static final StatedEventFacts STATED =
      new StatedEventFacts(
          new DateFact(
              Instant.parse("2027-03-12T00:00:00Z"),
              DatePrecision.DAY,
              "confs-tech",
              List.of(new SourcedValue("2027-03-13", "ai-deadlines")),
              true),
          new DateFact(
              Instant.parse("2027-03-14T00:00:00Z"),
              DatePrecision.DAY,
              "confs-tech",
              List.of(),
              false),
          "Zurich",
          EventKind.CONFERENCE,
          EventFormat.HYBRID);

  final List<SourceHit> hits =
      CatalogSearchSource.hits(
          INDEX,
          List.of(summit(), undated()),
          NOW,
          Map.of(SUMMIT, new Vitality(false, true, null, true)),
          Map.of(SUMMIT, STATED));

  @Test
  void aHitOfTheCatalogStatesEveryFactOfItsRowItsEventAndItsVitality() {
    assertThat(hits.getFirst())
        .isEqualTo(
            new SourceHit(
                "helper2-index",
                "7b0e3c52-6a6f-4a6e-9d0c-3f1f6a1c2d01",
                URI.create("https://www.summit.example/2027?ref=home"),
                CanonicalUrl.of("https://summit.example/2027"),
                null,
                "Agent Summit 2027",
                "Two days about AI agents.",
                ValueOrigin.AI_SUMMARY,
                ResourceType.EVENT,
                Audience.BOTH,
                TechLevel.ADVANCED,
                Set.of("agents"),
                Set.of("conference"),
                "CC-BY-4.0",
                Pricing.PAID,
                Set.of("web"),
                false,
                new DateFact(
                    Instant.parse("2026-05-01T00:00:00Z"),
                    DatePrecision.DAY,
                    "confs-tech",
                    List.of(),
                    false),
                new DateFact(
                    Instant.parse("2026-09-01T08:30:00Z"),
                    DatePrecision.INSTANT,
                    "github",
                    List.of(),
                    false),
                List.of(new PopularityMetric(PopularityKind.GITHUB_STARS, 42L, "github", NOW)),
                NOW,
                0,
                new DateFact(
                    Instant.parse("2027-03-12T00:00:00Z"),
                    DatePrecision.DAY,
                    "confs-tech",
                    List.of(new SourcedValue("2027-03-13", "ai-deadlines")),
                    true),
                new DateFact(
                    Instant.parse("2027-03-14T00:00:00Z"),
                    DatePrecision.DAY,
                    "confs-tech",
                    List.of(),
                    false),
                "Zurich",
                EventKind.CONFERENCE,
                EventFormat.HYBRID,
                Map.of(),
                Set.of("events"),
                null,
                Set.of("title"),
                new Vitality(false, true, null, true)));
  }

  @Test
  void aHitOfARowThatStatesLittleLeavesTheRestUnknown() {
    assertThat(hits.get(1))
        .isEqualTo(
            new SourceHit(
                "helper2-index",
                "7b0e3c52-6a6f-4a6e-9d0c-3f1f6a1c2d02",
                URI.create("https://tool.example"),
                CanonicalUrl.of("https://tool.example"),
                null,
                "Tool",
                null,
                ValueOrigin.SOURCE_PROVIDED,
                ResourceType.PRODUCT,
                Audience.USE_AI,
                null,
                Set.of(),
                Set.of(),
                null,
                null,
                Set.of(),
                null,
                DateFact.unknown(),
                DateFact.unknown(),
                List.of(),
                NOW,
                1));
  }
}
