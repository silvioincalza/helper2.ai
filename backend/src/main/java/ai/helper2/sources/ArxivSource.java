package ai.helper2.sources;

import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
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
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * arXiv papers in AI categories. API: https://info.arxiv.org/help/api/ — metadata is CC0; no more than
 * one request every 3 seconds, so this adapter self-throttles and reports RATE_LIMITED instead of
 * calling faster. Helper2 must not imply arXiv endorsement.
 */
public class ArxivSource implements SearchSource {

    static final String AI_CATEGORIES = "(cat:cs.AI OR cat:cs.CL OR cat:cs.LG OR cat:cs.CV OR cat:cs.MA OR cat:cs.IR OR cat:stat.ML)";
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(3);
    private static final String ATOM = "http://www.w3.org/2005/Atom";

    private final RestClient http;
    private final Clock clock;
    private final SourceDescriptor descriptor;
    private final AtomicReference<Instant> lastCall = new AtomicReference<>(Instant.MIN);

    public ArxivSource(SourceSettings settings, RestClient.Builder builder, Clock clock) {
        this.clock = clock;
        this.http = builder.baseUrl(settings.baseUrl()).build();
        this.descriptor = new SourceDescriptor("arxiv", "arXiv", SourceMode.LIVE, URI.create("https://arxiv.org"),
            URI.create("https://info.arxiv.org/help/api/tou.html"),
            "Thank you to arXiv for use of its open access interoperability.", EnumSet.of(ResourceType.PAPER),
            settings.timeout(), settings.cacheTtl(), settings.enabled(), 45);
    }

    @Override
    public SourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public List<SourceHit> search(SourceQuery query) {
        Instant now = clock.instant();
        Instant previous = lastCall.get();
        if (Duration.between(previous, now).compareTo(MIN_INTERVAL) < 0 || !lastCall.compareAndSet(previous, now)) {
            throw new SourceFailure.RateLimited(MIN_INTERVAL);
        }
        String terms = HttpClients.queryText(query)
            .replaceAll("[^\\p{L}\\p{N} -]", " ").trim().replaceAll("\\s+", " ");
        String searchQuery = "all:(" + terms + ") AND " + AI_CATEGORIES;
        String xml = HttpClients.getText(() -> http.get().uri(u -> u.path("/api/query").queryParam("search_query", searchQuery)
                .queryParam("max_results", Math.min(query.getLimit(), 20)).queryParam("sortBy", "relevance").build())
            .retrieve().body(String.class), clock);
        return parse(xml, now);
    }

    List<SourceHit> parse(String xml, Instant now) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            // No DTDs or external entities: the feed is untrusted input.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setExpandEntityReferences(false);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new SourceFailure.Unavailable("malformed response");
        }
        NodeList entries = doc.getElementsByTagNameNS(ATOM, "entry");
        List<SourceHit> hits = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element e = (Element) entries.item(i);
            String id = text(e, "id");
            String title = clean(text(e, "title"));
            CanonicalUrl canonical = CanonicalUrl.of(id == null ? null : id.replaceFirst("v\\d+$", "").replace("http://", "https://"));
            if (id == null || title == null || canonical == null) {
                continue;
            }
            Set<String> tags = new LinkedHashSet<>();
            NodeList cats = e.getElementsByTagNameNS(ATOM, "category");
            for (int c = 0; c < cats.getLength(); c++) {
                tags.add(((Element) cats.item(c)).getAttribute("term"));
            }
            String summary = clean(text(e, "summary"));
            hits.add(new SourceHit("arxiv", canonical.getValue(), URI.create(canonical.getValue()), canonical, null, title,
                summary == null ? null : summary.length() > 600 ? summary.substring(0, 597) + "…" : summary,
                ValueOrigin.SOURCE_PROVIDED, ResourceType.PAPER, Audience.BUILD_WITH_AI, null, Set.of(), tags, null, null,
                Set.of(), null,
                DateFact.fromSource(instant(text(e, "published")), "arxiv", now),
                DateFact.fromSource(instant(text(e, "updated")), "arxiv", now), List.of(), now, i));
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
