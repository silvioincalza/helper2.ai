package ai.helper2.sources.feeds;

import static ai.helper2.sources.SourceFixtures.live;
import static ai.helper2.sources.SourceFixtures.liveHttp;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.config.FeedProperties;
import ai.helper2.sources.support.RunInterval;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("live")
class NewsFeedCollectorLiveTest {

  @ParameterizedTest
  @CsvSource({
    "openai, https://openai.com/news/rss.xml",
    "hugging-face, https://huggingface.co/blog/feed.xml",
    "google-deepmind, https://deepmind.google/blog/rss.xml"
  })
  void aRealFeedGivesDatedNewsPosts(String id, String url) {
    FeedProperties.Feed feed =
        new FeedProperties.Feed(id, id, "Feed", URI.create(url), URI.create(url), List.of(), null);
    NewsFeedCollector collector =
        new NewsFeedCollector(
            live(null, null),
            feed,
            liveHttp(),
            Clock.systemUTC(),
            Resilience.NONE,
            new RunInterval(Duration.ZERO));

    CollectedBatch batch = collector.collect(null, null, 20);

    assertThat(batch.getHits()).isNotEmpty().hasSizeLessThanOrEqualTo(20);
    assertThat(batch.getHits())
        .allSatisfy(h -> assertThat(h.getType()).isEqualTo(ResourceType.NEWS));
    assertThat(batch.getHits().stream().map(SourceHit::getPublished))
        .anySatisfy(d -> assertThat(d.getValue()).isNotNull());
  }
}
