package ai.searchavo.sources.arxiv;

import ai.searchavo.platform.resilience.OutboundThrottle;
import ai.searchavo.search.SearchSource;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.search.SourceHit;
import ai.searchavo.search.SourceQuery;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.SourceMode;
import ai.searchavo.sources.support.HttpClients;
import ai.searchavo.sources.support.SourceSettings;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class ArxivSource implements SearchSource {

  private static final List<String> AI_CATEGORY_IDS =
      List.of("cs.AI", "cs.CL", "cs.LG", "cs.CV", "cs.MA", "cs.IR", "stat.ML");
  static final String AI_CATEGORIES =
      AI_CATEGORY_IDS.stream().map(c -> "cat:" + c).collect(Collectors.joining(" OR ", "(", ")"));
  public static final Duration MIN_INTERVAL = Duration.ofSeconds(3);
  private static final String SHARED_SLOT = "arxiv";

  public static boolean takesSlot(OutboundThrottle throttle) {
    return throttle.tryAcquire(SHARED_SLOT, MIN_INTERVAL);
  }

  public static boolean isAboutAi(SourceHit paper) {
    return AI_CATEGORY_IDS.stream().anyMatch(paper.getTags()::contains);
  }

  private static final String ATOM = "http://www.w3.org/2005/Atom";

  private final RestClient http;
  private final Clock clock;
  private final SourceDescriptor descriptor;
  private final OutboundThrottle throttle;

  public ArxivSource(
      SourceSettings settings, RestClient.Builder builder, Clock clock, OutboundThrottle throttle) {
    this.clock = clock;
    this.throttle = throttle;
    this.http = builder.baseUrl(settings.baseUrl()).build();
    this.descriptor =
        SourceDescriptor.external(
                "arxiv",
                "arXiv",
                "arXiv",
                SourceMode.LIVE,
                URI.create("https://arxiv.org"),
                EnumSet.of(ResourceType.PAPER),
                settings.timeout(),
                settings.cacheTtl())
            .withTerms(URI.create("https://info.arxiv.org/help/api/tou.html"))
            .withAttribution("Thank you to arXiv for use of its open access interoperability.")
            .enabledWhen(settings.enabled())
            .withPriority(45)
            .filteringByDate()
            .browsing();
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public List<SourceHit> search(SourceQuery query) {
    if (!takesSlot(throttle)) {
      throw new SourceFailure.RateLimited(MIN_INTERVAL);
    }
    Instant now = clock.instant();
    String searchQuery = searchQuery(query);
    String sortBy = query.getBrowse() ? "submittedDate" : "relevance";
    String xml =
        HttpClients.getText(
            () ->
                http.get()
                    .uri(u ->
                        u.path("/api/query")
                            .queryParam("search_query", searchQuery)
                            .queryParam("max_results", Math.min(query.getLimit(), 20))
                            .queryParam("sortBy", sortBy)
                            .build())
                    .retrieve()
                    .body(String.class),
            clock);
    return parse(xml, now);
  }

  private static String plainWords(String text) {
    return text.replaceAll("[^\\p{L}\\p{N} -]", " ").trim().replaceAll("\\s+", " ");
  }

  static String searchQuery(SourceQuery query) {
    String terms = plainWords(query.getKeywords());
    String q =
        query.getBrowse() || terms.isEmpty()
            ? AI_CATEGORIES
            : "all:(" + terms + ") AND " + AI_CATEGORIES;
    if (query.getPublishedAfter() != null) {
      q +=
          " AND submittedDate:["
              + query.getPublishedAfter().toString().replace("-", "")
              + "0000 TO 299912312359]";
    }
    return q;
  }

  List<SourceHit> parse(String xml, Instant now) {
    return papers(xml, "arxiv", now);
  }

  public static List<SourceHit> papers(String xml, String sourceId, Instant now) {
    Document doc;
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setExpandEntityReferences(false);
      doc =
          f.newDocumentBuilder()
              .parse(
                  new ByteArrayInputStream(
                      xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new SourceFailure.Unavailable("malformed response");
    }
    NodeList entries = doc.getElementsByTagNameNS(ATOM, "entry");
    List<SourceHit> hits = new ArrayList<>();
    for (int i = 0; i < entries.getLength(); i++) {
      Element e = (Element) entries.item(i);
      String id = text(e, "id");
      String title = clean(text(e, "title"));
      CanonicalUrl canonical =
          CanonicalUrl.of(
              id == null ? null : id.replaceFirst("v\\d+$", "").replace("http://", "https://"));
      if (id == null || title == null || canonical == null) {
        continue;
      }
      Set<String> tags = new LinkedHashSet<>();
      NodeList cats = e.getElementsByTagNameNS(ATOM, "category");
      for (int c = 0; c < cats.getLength(); c++) {
        tags.add(((Element) cats.item(c)).getAttribute("term"));
      }
      String summary = clean(text(e, "summary"));
      hits.add(
          new SourceHit(
                  sourceId,
                  canonical.getValue(),
                  URI.create(canonical.getValue()),
                  canonical,
                  title,
                  ResourceType.PAPER,
                  now)
              .withDescription(
                  summary == null
                      ? null
                      : summary.length() > 600 ? summary.substring(0, 597) + "…" : summary)
              .withAudience(Audience.BUILD_WITH_AI)
              .withTags(tags)
              .withPublished(DateFact.fromSource(instant(text(e, "published")), "arxiv", now))
              .withUpdated(DateFact.fromSource(instant(text(e, "updated")), "arxiv", now))
              .withRank(i));
    }
    return hits;
  }

  private static String text(Element parent, String local) {
    NodeList n = parent.getElementsByTagNameNS(ATOM, local);
    return n.getLength() == 0 ? null : n.item(0).getTextContent();
  }

  private static String clean(String s) {
    return s == null ? null : s.replaceAll("\\s+", " ").trim();
  }

  private static Instant instant(String s) {
    try {
      return s == null ? null : Instant.parse(s.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }
}
