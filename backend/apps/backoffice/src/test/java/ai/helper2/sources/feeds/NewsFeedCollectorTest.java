package ai.helper2.sources.feeds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.config.FeedProperties;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NewsFeedCollectorTest {

  static final String RSS =
      """
      <rss version="2.0"><channel><title>Vendor</title>
        <item><title>New reasoning model</title>
          <link>https://vendor.example/news/reasoning</link>
          <pubDate>Mon, 19 Oct 2026 17:00:00 GMT</pubDate>
          <description>A model for hard problems.</description></item>
        <item><title>Our new data center in Texas</title>
          <link>https://vendor.example/news/texas</link></item>
        <item><title>Agents for gamers</title>
          <link>https://vendor.example/news/agents?utm_source=rss</link></item>
      </channel></rss>
      """;

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  private static FeedProperties.Feed feed(List<String> requireWords) {
    return new FeedProperties.Feed(
        "vendor",
        "Vendor",
        "Vendor",
        URI.create("https://vendor.example/news/rss.xml"),
        URI.create("https://vendor.example/news"),
        requireWords,
        null);
  }

  private NewsFeedCollector collector(FeedProperties.Feed feed, RunInterval interval) {
    return new NewsFeedCollector(
        new SourceSettings(true, null, null, Duration.ofSeconds(2), Duration.ZERO),
        feed,
        TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        interval,
        URI.create(server.baseUrl() + "/news/rss.xml"));
  }

  @Test
  void eachPostIsANewsHitOfTheFeedWithItsDateAndSummary() {
    server.route("/news/rss.xml", FixtureServer.Reply.ok(RSS));

    CollectedBatch batch =
        collector(feed(List.of()), new RunInterval(Duration.ofDays(1))).collect(null, null, 50);

    assertThat(batch.getHits()).hasSize(3);
    SourceHit first = batch.getHits().getFirst();
    assertThat(first.getSourceId()).isEqualTo("feed-vendor");
    assertThat(first.getType()).isEqualTo(ResourceType.NEWS);
    assertThat(first.getTitle()).isEqualTo("New reasoning model");
    assertThat(first.getDescription()).isEqualTo("A model for hard problems.");
    assertThat(first.getUrl()).isEqualTo(URI.create("https://vendor.example/news/reasoning"));
    assertThat(first.getPublished().getValue()).isEqualTo(Instant.parse("2026-10-19T17:00:00Z"));
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
    assertThat(batch.getNextCursor()).isNull();
    assertThat(server.requestHeaders.getFirst().get("Accept").getFirst())
        .contains("application/rss+xml", "application/atom+xml");
  }

  @Test
  void aUtf8FeedWithoutACharsetKeepsItsLetters() {
    server.route(
        "/news/rss.xml",
        new FixtureServer.Reply(
            200,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><item><title>Mod\u00e8le f\u00fcr KI \u2014 \u65b0</title>
              <link>https://vendor.example/news/modele</link></item></channel></rss>
            """,
            Map.of("Content-Type", "application/rss+xml"),
            0));

    CollectedBatch batch =
        collector(feed(List.of()), new RunInterval(Duration.ofDays(1))).collect(null, null, 50);

    assertThat(batch.getHits().getFirst().getTitle())
        .isEqualTo("Mod\u00e8le f\u00fcr KI \u2014 \u65b0");
  }

  @Test
  void requiredWordsKeepOnlyThePostsAboutAi() {
    server.route("/news/rss.xml", FixtureServer.Reply.ok(RSS));

    CollectedBatch batch =
        collector(feed(List.of("model", "agent*")), new RunInterval(Duration.ofDays(1)))
            .collect(null, null, 50);

    assertThat(batch.getHits())
        .extracting(SourceHit::getTitle)
        .containsExactly("New reasoning model", "Agents for gamers");
  }

  @Test
  void aWordMatchesOnlyAtTheStartOfAWord() {
    server.route("/news/rss.xml", FixtureServer.Reply.ok(RSS));

    CollectedBatch batch =
        collector(feed(List.of("ai")), new RunInterval(Duration.ofDays(1))).collect(null, null, 50);

    assertThat(batch.getHits()).isEmpty();
  }

  @Test
  void theRunBudgetCapsThePosts() {
    server.route("/news/rss.xml", FixtureServer.Reply.ok(RSS));

    CollectedBatch batch =
        collector(feed(List.of()), new RunInterval(Duration.ofDays(1))).collect(null, null, 2);

    assertThat(batch.getHits()).hasSize(2);
  }

  @Test
  void aFeedReadTodayIsNotReadAgainTheSameDay() {
    CollectedBatch batch =
        collector(feed(List.of()), new RunInterval(Duration.ofDays(1)))
            .collect(SourceFixtures.CLOCK.instant().toString(), null, 50);

    assertThat(batch.getNotDue()).isTrue();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void aServerErrorIsAFailedRun() {
    server.route("/news/rss.xml", new FixtureServer.Reply(503, "down", Map.of(), 0));

    assertThatThrownBy(() ->
            collector(feed(List.of()), new RunInterval(Duration.ofDays(1))).collect(null, null, 50))
        .isInstanceOf(SourceFailure.Unavailable.class);
  }

  @Test
  void theDescriptorNamesThePublisherAndItsFeed() {
    var descriptor =
        collector(feed(List.of()), new RunInterval(Duration.ofDays(1))).getDescriptor();

    assertThat(descriptor.getId()).isEqualTo("feed-vendor");
    assertThat(descriptor.getName()).isEqualTo("Vendor news");
    assertThat(descriptor.getResourceTypes()).containsExactly(ResourceType.NEWS);
    assertThat(descriptor.getEnabled()).isTrue();
  }
}
