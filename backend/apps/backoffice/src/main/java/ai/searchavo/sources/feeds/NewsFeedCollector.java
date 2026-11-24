package ai.searchavo.sources.feeds;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.CollectedBatch;
import ai.searchavo.scout.Collector;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceHit;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.sources.config.CollectorDescriptors;
import ai.searchavo.sources.config.FeedProperties;
import ai.searchavo.sources.support.HttpClients;
import ai.searchavo.sources.support.RunInterval;
import ai.searchavo.sources.support.SourceSettings;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

public class NewsFeedCollector implements Collector {

  static final String POLICY = "collector-news-feeds";

  static final String ACCEPT =
      "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8";

  private static final int MAX_POSTS = 100;

  private final FeedProperties.Feed feed;
  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final URI fetchUrl;
  private final SourceDescriptor descriptor;
  private final List<Pattern> requiredWords;
  private final FeedReader reader = new FeedReader();

  public NewsFeedCollector(
      SourceSettings settings,
      FeedProperties.Feed feed,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval) {
    this(settings, feed, builder, clock, resilience, interval, feed.url());
  }

  NewsFeedCollector(
      SourceSettings settings,
      FeedProperties.Feed feed,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      URI fetchUrl) {
    this.feed = feed;
    this.http = builder.build();
    this.clock = clock;
    this.resilience = resilience;
    this.interval = interval;
    this.fetchUrl = fetchUrl;
    this.descriptor = CollectorDescriptors.newsFeed(settings, feed);
    this.requiredWords = feed.requireWords().stream().map(NewsFeedCollector::word).toList();
    if (descriptor.getEnabled()) {
      resilience.require(POLICY);
    }
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public Duration runEvery() {
    return interval.every();
  }

  @Override
  public CollectedBatch collect(String checkpoint, String resumeCursor, int runBudget) {
    Instant startedAt = clock.instant();
    if (!interval.allowsPass(checkpoint, resumeCursor, startedAt)) {
      return CollectedBatch.notDue();
    }
    ResponseEntity<byte[]> response = fetch();
    byte[] body = response.getBody() == null ? new byte[0] : response.getBody();
    List<FeedItem> posts = reader.read(body, charsetOf(response), feed.url());
    return new CollectedBatch(
        hits(posts, Math.min(runBudget, MAX_POSTS), startedAt), null, startedAt.toString());
  }

  private ResponseEntity<byte[]> fetch() {
    return resilience.call(
        POLICY,
        () ->
            HttpClients.get(
                () ->
                    http.get()
                        .uri(fetchUrl)
                        .header(HttpHeaders.ACCEPT, ACCEPT)
                        .retrieve()
                        .toEntity(byte[].class),
                clock));
  }

  private static @Nullable Charset charsetOf(ResponseEntity<byte[]> response) {
    MediaType type = response.getHeaders().getContentType();
    return type == null ? null : type.getCharset();
  }

  private List<SourceHit> hits(List<FeedItem> posts, int limit, Instant now) {
    List<SourceHit> hits = new ArrayList<>();
    for (FeedItem post : posts) {
      if (hits.size() >= limit) {
        break;
      }
      if (!isAbout(post)) {
        HttpClients.dropped(feed.sourceId(), "off-topic");
        continue;
      }
      SourceHit hit = toHit(post, now, hits.size());
      if (hit == null) {
        HttpClients.dropped(feed.sourceId(), "bad-link");
        continue;
      }
      hits.add(hit);
    }
    return List.copyOf(hits);
  }

  private boolean isAbout(FeedItem post) {
    if (requiredWords.isEmpty()) {
      return true;
    }
    String text = (post.title() + " " + (post.summary() == null ? "" : post.summary()));
    return requiredWords.stream().anyMatch(w -> w.matcher(text).find());
  }

  private SourceHit toHit(FeedItem post, Instant now, int rank) {
    CanonicalUrl canonical = CanonicalUrl.of(post.link().toString());
    if (canonical == null) {
      return null;
    }
    return new SourceHit(
            feed.sourceId(),
            canonical.getValue(),
            HttpClients.link(post.link().toString(), canonical),
            canonical,
            post.title(),
            ResourceType.NEWS,
            now)
        .withDescription(post.summary())
        .withAudience(Audience.BOTH)
        .withPublished(DateFact.fromSource(post.published(), feed.sourceId(), now))
        .withRank(rank);
  }

  private static Pattern word(String word) {
    boolean prefix = word.endsWith("*");
    String stem = prefix ? word.substring(0, word.length() - 1) : word;
    return Pattern.compile(
        "(?<![\\p{L}\\p{N}])"
            + Pattern.quote(stem.toLowerCase(Locale.ROOT))
            + (prefix ? "" : "(?![\\p{L}\\p{N}])"),
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }
}
