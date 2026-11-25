package ai.searchavo.sources.hackernews;

import static ai.searchavo.sources.SourceFixtures.live;
import static ai.searchavo.sources.SourceFixtures.liveHttp;
import static ai.searchavo.sources.SourceFixtures.query;
import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.search.SourceHit;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class HackerNewsSourceLiveTest {

  @Test
  void searchFindsStoriesThatLinkAPage() {
    HackerNewsSource source =
        new HackerNewsSource(
            live("https://hn.algolia.com", null),
            liveHttp(),
            Clock.systemUTC(),
            ShadowLibraries.of("z-lib"));

    List<SourceHit> hits = source.search(query("llm"));

    assertThat(hits)
        .isNotEmpty()
        .allSatisfy(h -> assertThat(h.getUrl().getHost()).isNotEqualTo("news.ycombinator.com"));
  }
}
