package ai.searchavo.sources.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.search.SourceHit;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.DatePrecision;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.ValueOrigin;
import ai.searchavo.sources.SourceFixtures;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DemoSourcesConfigurationTest {

  final List<SourceHit> fixtures =
      DemoSourcesConfiguration.fixtures(SourceFixtures.CLOCK.instant());

  @Test
  void aDemoHitStatesItsLicenceItsDaysAndTheUseCasesItsWordsName() {
    assertThat(fixtures.getFirst())
        .isEqualTo(
            new SourceHit(
                "demo-catalog",
                "demo-pg-mcp",
                URI.create("https://github.com/searchavo-demo/pg-mcp"),
                CanonicalUrl.of("https://github.com/searchavo-demo/pg-mcp"),
                null,
                "Demo Postgres MCP server",
                "Fixture: MCP server exposing a Postgres database to AI assistants.",
                ValueOrigin.DEMO,
                ResourceType.MCP_SERVER,
                Audience.BUILD_WITH_AI,
                null,
                Set.of(),
                Set.of("database", "mcp", "postgres"),
                "MIT",
                null,
                Set.of(),
                true,
                new DateFact(
                    Instant.parse("2025-01-15T00:00:00Z"),
                    DatePrecision.INSTANT,
                    "demo-catalog",
                    List.of(),
                    false),
                new DateFact(
                    Instant.parse("2026-10-01T00:00:00Z"),
                    DatePrecision.INSTANT,
                    "demo-catalog",
                    List.of(),
                    false),
                List.of(),
                SourceFixtures.CLOCK.instant()));
  }

  @Test
  void aDemoHitWithoutALicenceOrDaysStatesNone() {
    assertThat(fixtures.get(1))
        .isEqualTo(
            new SourceHit(
                "demo-catalog",
                "demo-writer",
                URI.create("https://demo.searchavo.ai/writer"),
                CanonicalUrl.of("https://demo.searchavo.ai/writer"),
                null,
                "Demo Writing Assistant",
                "Fixture: drafts and rewrites emails, blog posts and newsletters. Supports translation.",
                ValueOrigin.DEMO,
                ResourceType.PRODUCT,
                Audience.USE_AI,
                null,
                Set.of("writing"),
                Set.of("newsletter", "translation", "writing"),
                null,
                null,
                Set.of(),
                null,
                DateFact.unknown(),
                DateFact.unknown(),
                List.of(),
                SourceFixtures.CLOCK.instant(),
                1));
  }
}
