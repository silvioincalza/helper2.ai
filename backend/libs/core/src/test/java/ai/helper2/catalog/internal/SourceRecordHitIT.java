package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.search.ListMatch;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.SourceContext;
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
import ai.helper2.shared.ResourceId;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
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
