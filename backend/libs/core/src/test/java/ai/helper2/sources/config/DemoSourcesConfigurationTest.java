package ai.helper2.sources.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.DatePrecision;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.sources.SourceFixtures;
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
                URI.create("https://github.com/helper2-demo/pg-mcp"),
                CanonicalUrl.of("https://github.com/helper2-demo/pg-mcp"),
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
                URI.create("https://demo.helper2.ai/writer"),
                CanonicalUrl.of("https://demo.helper2.ai/writer"),
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
