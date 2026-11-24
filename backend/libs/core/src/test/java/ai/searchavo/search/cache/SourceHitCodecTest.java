package ai.searchavo.search.cache;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.search.CachedHits;
import ai.searchavo.search.ListMatch;
import ai.searchavo.search.PopularityKind;
import ai.searchavo.search.PopularityMetric;
import ai.searchavo.search.SourceHit;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.EventFormat;
import ai.searchavo.shared.EventKind;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.ValueOrigin;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SourceHitCodecTest {

  private final SourceHitCodec codec = new SourceHitCodec(JsonMapper.builder().build());
  private final Instant now = Instant.parse("2026-10-27T10:00:00Z");

  @Test
  void anEventKeepsItsDaysPlaceKindAndFormatThroughTheCache() {
    SourceHit event =
        new SourceHit(
            "live-web",
            "x",
            URI.create("https://conf.example.org/2026"),
            CanonicalUrl.of("https://conf.example.org/2026"),
            null,
            "Conf 2026",
            "An AI conference",
            ValueOrigin.SOURCE_PROVIDED,
            ResourceType.EVENT,
            Audience.BOTH,
            null,
            Set.of(),
            Set.of("conference"),
            null,
            null,
            Set.of(),
            null,
            DateFact.fromSource(Instant.parse("2026-09-01T00:00:00Z"), "live-web", now),
            DateFact.unknown(),
            List.of(new PopularityMetric(PopularityKind.GITHUB_STARS, 42, "live-web", now)),
            now,
            3,
            DateFact.scheduledDay(LocalDate.parse("2026-12-06"), "live-web"),
            DateFact.scheduledDay(LocalDate.parse("2026-12-12"), "live-web"),
            "Sydney, Australia",
            EventKind.HACKATHON,
            EventFormat.HYBRID);

    CachedHits back = codec.decode(codec.encode(new CachedHits(List.of(event), now)));

    assertThat(back).isNotNull();
    assertThat(back.getHits()).containsExactly(event);
  }

  @Test
  void whereAListFoundTheHitSurvivesTheCache() {
    SourceHit hit =
        new SourceHit(
            "searchavo-index",
            "y",
            URI.create("https://github.com/a/store"),
            CanonicalUrl.of("https://github.com/a/store"),
            null,
            "a/store",
            null,
            ValueOrigin.SOURCE_PROVIDED,
            ResourceType.LIBRARY,
            Audience.BUILD_WITH_AI,
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
            now,
            0,
            DateFact.unknown(),
            DateFact.unknown(),
            null,
            null,
            null,
            java.util.Map.of(),
            java.util.Set.of(),
            new ListMatch("acme/awesome-llm", "Tools > Vector databases"));

    CachedHits back = codec.decode(codec.encode(new CachedHits(List.of(hit), now)));

    assertThat(back).isNotNull();
    assertThat(back.getHits()).containsExactly(hit);
  }

  @Test
  void aValueANewerReleaseWroteReadsAsItsFallback() {
    SourceHit hit =
        new SourceHit(
            "searchavo-index",
            "z",
            URI.create("https://github.com/a/awesome"),
            CanonicalUrl.of("https://github.com/a/awesome"),
            null,
            "a/awesome",
            null,
            ValueOrigin.SOURCE_PROVIDED,
            ResourceType.LIBRARY,
            Audience.BUILD_WITH_AI,
            null,
            Set.of(),
            Set.of(),
            null,
            null,
            Set.of(),
            null,
            DateFact.unknown(),
            DateFact.unknown(),
            List.of(new PopularityMetric(PopularityKind.GITHUB_STARS, 42, "searchavo-index", now)),
            now,
            0,
            DateFact.unknown(),
            DateFact.unknown(),
            null,
            null,
            null,
            java.util.Map.of(),
            java.util.Set.of(),
            null);
    String newer =
        codec
            .encode(new CachedHits(List.of(hit), now))
            .replace("\"LIBRARY\"", "\"SOMETHING_NEW\"")
            .replace("\"BUILD_WITH_AI\"", "\"SOMEONE_NEW\"")
            .replace("\"GITHUB_STARS\"", "\"NEW_COUNT\"");

    CachedHits back = codec.decode(newer);

    assertThat(back).isNotNull();
    assertThat(back.getHits())
        .singleElement()
        .satisfies(h -> {
          assertThat(h.getType()).isEqualTo(ResourceType.OTHER);
          assertThat(h.getAudience()).isEqualTo(Audience.UNKNOWN);
          assertThat(h.getPopularity()).isEmpty();
        });
  }

  @Test
  void entriesOfAnotherVersionAreAMiss() {
    String encoded = codec.encode(new CachedHits(List.of(), now));
    assertThat(
            codec.decode(
                encoded.replace(
                    "\"v\":" + SourceHitCodec.VERSION, "\"v\":" + (SourceHitCodec.VERSION + 1))))
        .isNull();
  }
}
