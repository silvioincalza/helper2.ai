package ai.helper2.sources.hackernews;

import static ai.helper2.sources.SourceFixtures.live;
import static ai.helper2.sources.SourceFixtures.liveHttp;
import static ai.helper2.sources.SourceFixtures.query;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.search.SourceHit;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class HackerNewsSourceLiveTest {

  @Test
  void searchFindsStoriesThatLinkAPage() {
    HackerNewsSource source =
        new HackerNewsSource(live("https://hn.algolia.com", null), liveHttp(), Clock.systemUTC());

    List<SourceHit> hits = source.search(query("llm"));

    assertThat(hits)
        .isNotEmpty()
        .allSatisfy(h -> assertThat(h.getUrl().getHost()).isNotEqualTo("news.ycombinator.com"));
  }
}
