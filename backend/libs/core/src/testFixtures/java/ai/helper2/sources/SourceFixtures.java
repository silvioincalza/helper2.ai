package ai.helper2.sources;

import ai.helper2.search.ShadowLibraries;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.web.client.RestClient;

public final class SourceFixtures {
  public static final ShadowLibraries SHADOW_LIBRARIES = ShadowLibraries.of("z-lib", "libgen");

  public static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC);

  private static final Duration LIVE_TIMEOUT = Duration.ofSeconds(30);

  private SourceFixtures() {}

  public static SourceHit hit(String id, int rank) {
    String url = "https://example.org/" + id;
    return new SourceHit(
            "test-source",
            id,
            URI.create(url),
            CanonicalUrl.of(url),
            id,
            ResourceType.LIBRARY,
            CLOCK.instant())
        .withAudience(Audience.BUILD_WITH_AI)
        .withRank(rank);
  }

  public static SourceSettings live(String baseUrl, String tokenVariable) {
    String token = tokenVariable == null ? "" : System.getenv().getOrDefault(tokenVariable, "");
    return new SourceSettings(true, baseUrl, token, LIVE_TIMEOUT, Duration.ZERO, null);
  }

  public static RestClient.Builder liveHttp() {
    return TestHttp.restClientBuilder(LIVE_TIMEOUT, ObservationRegistry.NOOP);
  }

  public static SourceQuery query(String text) {
    return new SourceQuery(text, List.of(), null, 10);
  }
}
