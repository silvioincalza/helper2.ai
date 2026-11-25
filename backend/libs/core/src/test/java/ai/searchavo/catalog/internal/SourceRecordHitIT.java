package ai.searchavo.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.TestcontainersConfiguration;
import ai.searchavo.catalog.CatalogWriter;
import ai.searchavo.catalog.DataOrigin;
import ai.searchavo.catalog.usecase.GetResourceDetail;
import ai.searchavo.search.ListMatch;
import ai.searchavo.search.PopularityKind;
import ai.searchavo.search.PopularityMetric;
import ai.searchavo.search.ResultMerger;
import ai.searchavo.search.ServedFrom;
import ai.searchavo.search.SourceContext;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceHit;
import ai.searchavo.search.Vitality;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.DatePrecision;
import ai.searchavo.shared.EventFormat;
import ai.searchavo.shared.EventKind;
import ai.searchavo.shared.Pricing;
import ai.searchavo.shared.ResourceId;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.SourceMode;
import ai.searchavo.shared.TechLevel;
import ai.searchavo.shared.ValueOrigin;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SourceRecordHitIT {

  static final Instant RETRIEVED = Instant.parse("2026-10-22T10:00:00Z");

  @Autowired CatalogWriter writer;
  @Autowired GetResourceDetail details;
  @Autowired JdbcClient jdbc;

  final String url = "https://github.com/acme/record-hit-" + UUID.randomUUID();

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM resource WHERE id = :id")
        .param("id", ResourceId.of(CanonicalUrl.of(url)).getValue())
        .update();
  }

  SourceHit stated() {
    return new SourceHit(
        "github",
        "acme/record-hit",
        URI.create(url + "?tab=readme"),
        CanonicalUrl.of(url),
        CanonicalUrl.of(url + "-repo"),
        "Record hit summit",
        "Two days about AI agents.",
        ValueOrigin.AI_SUMMARY,
        ResourceType.EVENT,
        Audience.BOTH,
        TechLevel.ADVANCED,
        Set.of("agents"),
        Set.of("conference"),
        "MIT",
        Pricing.PAID,
        Set.of("web"),
        true,
        new DateFact(
            Instant.parse("2026-05-01T00:00:00Z"), DatePrecision.DAY, "github", List.of(), false),
        new DateFact(
            Instant.parse("2026-09-01T08:30:00Z"),
            DatePrecision.INSTANT,
            "github",
            List.of(),
            false),
        List.of(
            new PopularityMetric(
                PopularityKind.GITHUB_STARS, 42, "github", Instant.parse("2026-10-01T00:00:00Z"))),
        RETRIEVED,
        3,
        new DateFact(
            Instant.parse("2027-03-12T00:00:00Z"), DatePrecision.DAY, "github", List.of(), false),
        new DateFact(
            Instant.parse("2027-03-14T00:00:00Z"), DatePrecision.DAY, "github", List.of(), false),
        "Zurich",
        EventKind.CONFERENCE,
        EventFormat.HYBRID,
        Map.of("stars", 42),
        Set.of("events"),
        new ListMatch("Awesome", "Events"),
        Set.of(),
        new Vitality(false, true, false, true));
  }

  static SourceContext github() {
    return new SourceContext(
        new SourceDescriptor(
            "github",
            "GitHub",
            "GitHub",
            true,
            SourceMode.LIVE,
            URI.create("https://github.com"),
            null,
            null,
            EnumSet.allOf(ResourceType.class),
            Duration.ofSeconds(1),
            Duration.ZERO),
        ServedFrom.LIVE);
  }

  @Test
  void theRecordOfASourceIsReadBackAsTheHitTheSourceStated() {
    writer.upsert(
        new ResultMerger().merge(List.of(stated()), Map.of("github", github())),
        DataOrigin.INDEXED,
        Set.of("github"));

    List<SourceHit> read =
        details.find(ResourceId.of(CanonicalUrl.of(url)).getValue()).orElseThrow().card().getHits();

    assertThat(read).hasSize(1);
    assertThat(read.getFirst())
        .isEqualTo(
            new SourceHit(
                "github",
                "acme/record-hit",
                URI.create(url + "?tab=readme"),
                CanonicalUrl.of(url),
                CanonicalUrl.of(url + "-repo"),
                "Record hit summit",
                "Two days about AI agents.",
                ValueOrigin.AI_SUMMARY,
                ResourceType.EVENT,
                Audience.BOTH,
                TechLevel.ADVANCED,
                Set.of("agents"),
                Set.of("conference"),
                "MIT",
                Pricing.PAID,
                Set.of("web"),
                true,
                new DateFact(
                    Instant.parse("2026-05-01T00:00:00Z"),
                    DatePrecision.DAY,
                    "github",
                    List.of(),
                    false),
                new DateFact(
                    Instant.parse("2026-09-01T08:30:00Z"),
                    DatePrecision.INSTANT,
                    "github",
                    List.of(),
                    false),
                List.of(
                    new PopularityMetric(
                        PopularityKind.GITHUB_STARS,
                        42L,
                        "github",
                        Instant.parse("2026-10-01T00:00:00Z"))),
                RETRIEVED,
                0,
                new DateFact(
                    Instant.parse("2027-03-12T00:00:00Z"),
                    DatePrecision.DAY,
                    "github",
                    List.of(),
                    false),
                new DateFact(
                    Instant.parse("2027-03-14T00:00:00Z"),
                    DatePrecision.DAY,
                    "github",
                    List.of(),
                    false),
                "Zurich",
                EventKind.CONFERENCE,
                EventFormat.HYBRID,
                Map.of(),
                Set.of(),
                null,
                Set.of(),
                new Vitality(false, true, false, true)));
  }
}
