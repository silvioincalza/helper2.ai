package ai.helper2.sources.feeds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.search.SourceFailure;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedReaderTest {

  static final URI FEED = URI.create("https://vendor.example/news/rss.xml");

  static final String RSS =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
        <channel>
          <title>Vendor News</title>
          <link>https://vendor.example/news</link>
          <item>
            <title><![CDATA[Introducing Model 5 & friends]]></title>
            <link>https://vendor.example/news/model-5/</link>
            <guid isPermaLink="false">model-5</guid>
            <pubDate>Mon, 02 Nov 2026 17:00:00 GMT</pubDate>
            <description><![CDATA[<p>Our <b>best</b> model&nbsp;yet.</p>]]></description>
            <category>Product</category>
          </item>
          <item>
            <title>A relative link</title>
            <link>/news/relative</link>
            <pubDate>Tue, 3 Nov 2026 09:30:00 +0100</pubDate>
          </item>
        </channel>
      </rss>
      """;

  static final String ATOM =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <feed xmlns="http://www.w3.org/2005/Atom">
        <title>Vendor Blog</title>
        <entry>
          <title type="html">Agents &amp;amp; tools</title>
          <link rel="replies" href="https://vendor.example/blog/agents#comments"/>
          <link rel="alternate" type="text/html" href="https://vendor.example/blog/agents"/>
          <id>tag:vendor.example,2026:agents</id>
          <published>2026-11-04T08:00:00Z</published>
          <updated>2026-11-05T08:00:00Z</updated>
          <summary>How we build agents.</summary>
        </entry>
        <entry>
          <title>Only updated</title>
          <link href="https://vendor.example/blog/updated"/>
          <updated>2026-11-06T10:15:00+02:00</updated>
        </entry>
      </feed>
      """;

  final FeedReader reader = new FeedReader();

  private List<FeedItem> read(String feed, URI feedUrl) {
    return reader.read(feed.getBytes(StandardCharsets.UTF_8), null, feedUrl);
  }

  @Test
  void withoutACharsetTheXmlDeclarationSetsTheEncoding() {
    String feed =
        """
        <?xml version="1.0" encoding="ISO-8859-1"?>
        <rss version="2.0"><channel><item><title>Caf\u00e9 f\u00fcr KI</title>
          <link>https://vendor.example/cafe</link></item></channel></rss>
        """;

    List<FeedItem> items = reader.read(feed.getBytes(StandardCharsets.ISO_8859_1), null, FEED);

    assertThat(items.getFirst().title()).isEqualTo("Caf\u00e9 f\u00fcr KI");
  }

  @Test
  void theHttpCharsetWinsOverTheXmlDeclaration() {
    String feed =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><item><title>Caf\u00e9</title>
          <link>https://vendor.example/cafe</link></item></channel></rss>
        """;

    List<FeedItem> items =
        reader.read(feed.getBytes(StandardCharsets.UTF_16), StandardCharsets.UTF_16, FEED);

    assertThat(items.getFirst().title()).isEqualTo("Caf\u00e9");
  }

  @Test
  void anRssItemKeepsItsTitleLinkDateAndAPlainSummary() {
    List<FeedItem> items = read(RSS, FEED);

    assertThat(items).hasSize(2);
    FeedItem first = items.getFirst();
    assertThat(first.title()).isEqualTo("Introducing Model 5 & friends");
    assertThat(first.link()).isEqualTo(URI.create("https://vendor.example/news/model-5/"));
    assertThat(first.published()).isEqualTo(Instant.parse("2026-11-02T17:00:00Z"));
    assertThat(first.summary()).isEqualTo("Our best model yet.");
  }

  @Test
  void aRelativeLinkIsReadAgainstTheFeedAndAOneDigitDayIsADate() {
    FeedItem second = read(RSS, FEED).get(1);

    assertThat(second.link()).isEqualTo(URI.create("https://vendor.example/news/relative"));
    assertThat(second.published()).isEqualTo(Instant.parse("2026-11-03T08:30:00Z"));
    assertThat(second.summary()).isNull();
  }

  @Test
  void anAtomEntryTakesTheAlternateLinkAndThePublishedDate() {
    List<FeedItem> items = read(ATOM, FEED);

    assertThat(items).hasSize(2);
    FeedItem first = items.getFirst();
    assertThat(first.title()).isEqualTo("Agents & tools");
    assertThat(first.link()).isEqualTo(URI.create("https://vendor.example/blog/agents"));
    assertThat(first.published()).isEqualTo(Instant.parse("2026-11-04T08:00:00Z"));
    assertThat(first.summary()).isEqualTo("How we build agents.");
  }

  @Test
  void anAtomEntryWithoutPublishedFallsBackToUpdated() {
    FeedItem second = read(ATOM, FEED).get(1);

    assertThat(second.link()).isEqualTo(URI.create("https://vendor.example/blog/updated"));
    assertThat(second.published()).isEqualTo(Instant.parse("2026-11-06T08:15:00Z"));
  }

  @Test
  void anElementOfAnotherNamespaceIsNotTakenForTheRssOne() {
    String feed =
        """
        <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
          <channel><item>
            <title>Model 5</title>
            <link>https://vendor.example/model-5</link>
            <media:description>A photo of a robot</media:description>
            <description>&lt;img src="x.png"&gt;Our best model yet.</description>
            <media:title>Hero image</media:title>
          </item></channel>
        </rss>
        """;

    FeedItem item = read(feed, FEED).getFirst();

    assertThat(item.title()).isEqualTo("Model 5");
    assertThat(item.summary()).isEqualTo("Our best model yet.");
  }

  @Test
  void aSummaryThatOnlyRepeatsACategoryIsNoSummary() {
    String feed =
        """
        <rss version="2.0"><channel><item>
          <title>Signs of hypertension</title>
          <link>https://vendor.example/hypertension</link>
          <description>Health &amp; Bioscience</description>
          <category>Health &amp; Bioscience</category>
          <category>Machine Intelligence</category>
        </item></channel></rss>
        """;

    assertThat(read(feed, FEED).getFirst().summary()).isNull();
  }

  @Test
  void aFeedWithOneItemIsStillAList() {
    String one =
        """
        <rss version="2.0"><channel><title>x</title>
          <item><title>Only one</title><link>https://vendor.example/one</link></item>
        </channel></rss>
        """;

    assertThat(read(one, FEED)).extracting(FeedItem::title).containsExactly("Only one");
  }

  @Test
  void anItemWithoutTitleOrWebLinkIsLeftOutAndAnUnreadableDateIsUnknown() {
    String feed =
        """
        <rss version="2.0"><channel>
          <item><link>https://vendor.example/no-title</link></item>
          <item><title>Mail link</title><link>mailto:news@vendor.example</link></item>
          <item><title>Odd date</title><link>https://vendor.example/odd</link>
            <pubDate>sometime last week</pubDate></item>
        </channel></rss>
        """;

    List<FeedItem> items = read(feed, FEED);

    assertThat(items).extracting(FeedItem::title).containsExactly("Odd date");
    assertThat(items.getFirst().published()).isNull();
  }

  @Test
  void aLongSummaryIsCutAtAWordBoundary() {
    String words = "word ".repeat(200);
    String feed =
        """
        <rss version="2.0"><channel><item><title>Long</title>
          <link>https://vendor.example/long</link><description>%s</description>
        </item></channel></rss>
        """
            .formatted(words);

    String summary = read(feed, FEED).getFirst().summary();

    assertThat(summary).hasSizeLessThanOrEqualTo(FeedReader.MAX_SUMMARY + 1).endsWith("…");
    assertThat(summary).doesNotContain("wor…");
  }

  @Test
  void aDoctypeIsRefusedSoNoEntityIsExpanded() {
    String xxe =
        """
        <?xml version="1.0"?>
        <!DOCTYPE rss [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
        <rss version="2.0"><channel><item><title>&secret;</title>
          <link>https://vendor.example/x</link></item></channel></rss>
        """;

    assertThatThrownBy(() -> read(xxe, FEED))
        .isInstanceOf(SourceFailure.Unavailable.class)
        .hasMessageContaining("malformed");
  }

  @Test
  void somethingThatIsNotAFeedIsAMalformedResponse() {
    assertThatThrownBy(() -> read("<html><body>Not found</body></html>", FEED))
        .isInstanceOf(SourceFailure.Unavailable.class);
    assertThatThrownBy(() -> read("not xml at all", FEED))
        .isInstanceOf(SourceFailure.Unavailable.class);
  }
}
