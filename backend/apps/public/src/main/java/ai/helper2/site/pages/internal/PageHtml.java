package ai.helper2.site.pages.internal;

import ai.helper2.site.monetisation.Monetisation;
import ai.helper2.site.pages.Fact;
import ai.helper2.site.pages.Link;
import ai.helper2.site.pages.PageBody;
import ai.helper2.site.pages.PageHead;
import ai.helper2.site.pages.Pages;
import ai.helper2.site.pages.Related;
import ai.helper2.site.pages.ResourcePathsKt;
import ai.helper2.site.pages.config.PagesProperties;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;

@Component
public class PageHtml {

  private static final String MARK = " data-h2-head";

  private final PageTemplate template;
  private final ObjectMapper json;
  private final String base;
  private final Monetisation monetisation;

  public PageHtml(
      PageTemplate template,
      ObjectMapper json,
      PagesProperties properties,
      Monetisation monetisation) {
    this.template = template;
    this.json = json;
    this.base = properties.publicBaseUrl();
    this.monetisation = monetisation;
  }

  public boolean available() {
    return template.present();
  }

  public String render(PageHead head, String nonce) {
    String safeNonce = esc(nonce);
    return template.fill(
        "<title" + MARK + ">" + esc(head.getTitle()) + "</title>",
        head(head, safeNonce),
        body(head.getBody()),
        safeNonce);
  }

  private String head(PageHead head, String nonce) {
    StringBuilder h = new StringBuilder();
    meta(h, "name", "robots", head.getRobots().getContent());
    String description = head.getDescription();
    if (description != null) {
      meta(h, "name", "description", description);
    }
    String canonicalPath = head.getCanonicalPath();
    if (canonicalPath != null) {
      String url = base + canonicalPath;
      h.append("<link")
          .append(MARK)
          .append(" rel=\"canonical\" href=\"")
          .append(esc(url))
          .append("\">");
      meta(h, "property", "og:type", "website");
      meta(h, "property", "og:site_name", Pages.BRAND);
      meta(h, "property", "og:title", head.getTitle());
      meta(h, "property", "og:url", url);
      if (description != null) {
        meta(h, "property", "og:description", description);
      }
      meta(h, "name", "twitter:card", "summary");
    }
    monetisation
        .adsenseClient()
        .ifPresent(c ->
            h.append("<meta name=\"google-adsense-account\" content=\"")
                .append(esc(c))
                .append("\">"));
    for (Map<String, Object> data : head.getJsonLd()) {
      h.append("<script")
          .append(MARK)
          .append(" nonce=\"")
          .append(nonce)
          .append("\" type=\"application/ld+json\">")
          .append(scriptSafe(json.writeValueAsString(data)))
          .append("</script>");
    }
    return h.toString();
  }

  private static void meta(StringBuilder h, String key, String name, String content) {
    h.append("<meta")
        .append(MARK)
        .append(' ')
        .append(key)
        .append("=\"")
        .append(esc(name))
        .append("\" content=\"")
        .append(esc(content))
        .append("\">");
  }

  private String body(@Nullable PageBody body) {
    if (body == null) {
      return "";
    }
    StringBuilder b =
        new StringBuilder("<main class=\"h2-prerender\"><h1>")
            .append(esc(body.getHeading()))
            .append("</h1>");
    String description = body.getDescription();
    if (description != null) {
      b.append("<p>").append(esc(description)).append("</p>");
    }
    list(b, body.getFacts(), (Fact f) -> "<b>" + esc(f.getName()) + ":</b> " + esc(f.getValue()));
    if (!body.getSources().isEmpty()) {
      b.append("<h2>Sources</h2>");
      list(b, body.getSources(), (Link l) -> sourceLink(l));
    }
    if (!body.getRelated().isEmpty()) {
      b.append("<h2>Related</h2>");
      list(
          b,
          body.getRelated(),
          (Related r) -> link(ResourcePathsKt.resourcePath(r.getId(), r.getTitle()), r.getTitle()));
    }
    return b.append("</main>").toString();
  }

  private static <T> void list(
      StringBuilder b, List<T> items, java.util.function.Function<T, String> item) {
    if (items.isEmpty()) {
      return;
    }
    b.append("<ul>");
    items.forEach(i -> b.append("<li>").append(item.apply(i)).append("</li>"));
    b.append("</ul>");
  }

  private static String sourceLink(Link l) {
    String scheme = l.getUrl().getScheme();
    boolean web = "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    return web ? outboundLink(l.getUrl().toString(), l.getName()) : esc(l.getName());
  }

  private static String outboundLink(String href, String text) {
    return "<a href=\"" + esc(href) + "\" rel=\"nofollow ugc\">" + esc(text) + "</a>";
  }

  private static String link(String href, String text) {
    return "<a href=\"" + esc(href) + "\">" + esc(text) + "</a>";
  }

  static String esc(String text) {
    return HtmlUtils.htmlEscape(text, "UTF-8");
  }

  static String scriptSafe(String json) {
    return json.replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace(" ", "\\u2028")
        .replace(" ", "\\u2029");
  }
}
