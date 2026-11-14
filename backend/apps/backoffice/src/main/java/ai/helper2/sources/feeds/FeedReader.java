package ai.helper2.sources.feeds;

import ai.helper2.search.SourceFailure;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.StreamSupport;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.xml.XmlFactory;
import tools.jackson.dataformat.xml.XmlMapper;

public class FeedReader {

  static final int MAX_SUMMARY = 400;

  private static final DateTimeFormatter RFC_822_NAMED_ZONE =
      DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm[:ss] zzz", Locale.ENGLISH);

  private final XMLInputFactory input = safeInput();

  private final XmlMapper xml = new XmlMapper(new XmlFactory(input, null));

  public List<FeedItem> read(byte[] body, @Nullable Charset charset, URI feedUrl) {
    JsonNode root = parse(body, charset);
    JsonNode rssItems = root.path("channel").path("item");
    if (!rssItems.isMissingNode()) {
      return items(rssItems, item -> rssItem(item, feedUrl));
    }
    JsonNode atomEntries = root.path("entry");
    if (!atomEntries.isMissingNode() || root.has("title") && root.has("id")) {
      return items(atomEntries, entry -> atomEntry(entry, feedUrl));
    }
    if (root.has("channel")) {
      return List.of();
    }
    throw new SourceFailure.Unavailable("malformed response: not an RSS or Atom feed");
  }

  private JsonNode parse(byte[] body, @Nullable Charset charset) {
    try {
      JsonNode root =
          xml.readValue(new PrefixedForeignNames(streamOf(body, charset)), JsonNode.class);
      if (root == null || !root.isObject()) {
        throw new SourceFailure.Unavailable("malformed response: not an RSS or Atom feed");
      }
      return root;
    } catch (JacksonException | XMLStreamException | IOException e) {
      throw new SourceFailure.Unavailable("malformed response");
    }
  }

  private XMLStreamReader streamOf(byte[] body, @Nullable Charset charset)
      throws XMLStreamException {
    return charset == null
        ? input.createXMLStreamReader(new ByteArrayInputStream(body))
        : input.createXMLStreamReader(new StringReader(new String(body, charset)));
  }

  private interface ItemMapper {
    Optional<FeedItem> map(JsonNode node);
  }

  private static List<FeedItem> items(JsonNode nodes, ItemMapper mapper) {
    Iterable<JsonNode> each = nodes.isArray() ? nodes : List.of(nodes);
    List<FeedItem> items = new ArrayList<>();
    StreamSupport.stream(each.spliterator(), false)
        .map(mapper::map)
        .flatMap(Optional::stream)
        .forEach(items::add);
    return List.copyOf(items);
  }

  private static Optional<FeedItem> rssItem(JsonNode item, URI feedUrl) {
    String description = text(item.path("description"));
    return item(
        text(item.path("title")),
        webLink(text(item.path("link")), feedUrl),
        date(text(item.path("pubDate"))),
        isOneOf(description, item.path("category")) ? null : description);
  }

  private static boolean isOneOf(@Nullable String text, JsonNode categories) {
    String plainText = plain(text);
    if (plainText == null) {
      return false;
    }
    Iterable<JsonNode> each = categories.isArray() ? categories : List.of(categories);
    return StreamSupport.stream(each.spliterator(), false)
        .map(c -> plain(text(c)))
        .anyMatch(plainText::equalsIgnoreCase);
  }

  private static Optional<FeedItem> atomEntry(JsonNode entry, URI feedUrl) {
    Instant published = date(text(entry.path("published")));
    return item(
        text(entry.path("title")),
        webLink(alternateHref(entry.path("link")), feedUrl),
        published != null ? published : date(text(entry.path("updated"))),
        Optional.ofNullable(text(entry.path("summary"))).orElse(text(entry.path("content"))));
  }

  private static Optional<FeedItem> item(
      @Nullable String title,
      @Nullable URI link,
      @Nullable Instant published,
      @Nullable String summary) {
    String plainTitle = plain(title);
    if (plainTitle == null || link == null) {
      return Optional.empty();
    }
    return Optional.of(new FeedItem(plainTitle, link, published, shortened(plain(summary))));
  }

  private static @Nullable String alternateHref(JsonNode links) {
    Iterable<JsonNode> each = links.isArray() ? links : List.of(links);
    for (JsonNode link : each) {
      String rel = text(link.path("rel"));
      if (rel == null || rel.equals("alternate")) {
        return text(link.path("href"));
      }
    }
    return null;
  }

  private static @Nullable String text(JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (node.isArray()) {
      return node.isEmpty() ? null : text(node.get(0));
    }
    String value = node.isObject() ? node.path("").asString(null) : node.asString(null);
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static @Nullable URI webLink(@Nullable String raw, URI feedUrl) {
    if (raw == null) {
      return null;
    }
    try {
      URI link = feedUrl.resolve(raw.strip());
      String scheme = link.getScheme();
      boolean web = "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
      return web && link.getHost() != null ? link : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static @Nullable Instant date(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    for (DateTimeFormatter format :
        List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            RFC_822_NAMED_ZONE)) {
      try {
        return format == DateTimeFormatter.ISO_OFFSET_DATE_TIME
            ? OffsetDateTime.parse(raw, format).toInstant()
            : ZonedDateTime.parse(raw, format).toInstant();
      } catch (DateTimeParseException e) {
      }
    }
    return null;
  }

  private static @Nullable String plain(@Nullable String html) {
    if (html == null) {
      return null;
    }
    String text =
        HtmlUtils.htmlUnescape(html.replaceAll("<[^>]*>", " "))
            .replace(' ', ' ')
            .replaceAll("\\s+", " ")
            .strip();
    return text.isEmpty() ? null : text;
  }

  private static @Nullable String shortened(@Nullable String text) {
    if (text == null || text.length() <= MAX_SUMMARY) {
      return text;
    }
    int cut = text.lastIndexOf(' ', MAX_SUMMARY);
    return text.substring(0, cut > 0 ? cut : MAX_SUMMARY).strip() + "…";
  }

  private static XMLInputFactory safeInput() {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    return factory;
  }
}
