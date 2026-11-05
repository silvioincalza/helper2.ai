package ai.helper2.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.pages.config.PagesProperties;
import ai.helper2.pages.internal.PageHtml;
import ai.helper2.pages.internal.PageTemplate;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/**
 * The page's HTML: written into the shell, and safe whatever a source's text says (ADR 0050 §4).
 */
class PageHtmlTest {

  private static final String SHELL =
      "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\" /><title>Helper2.ai</title>"
          + "<script type=\"module\" src=\"/assets/index.js\"></script></head>"
          + "<body><div id=\"root\"></div></body></html>";
  private static final String HOSTILE =
      "</script><script>alert(1)</script>\" onload=\"x\" <b>&amp; \u2028";
  private static final UUID ID = UUID.fromString("8a0e6c1e-2f43-5b6e-9d0a-1f1e2d3c4b5a");

  @TempDir Path dist;

  private PageHtml html(String shell) throws IOException {
    Path index = Files.writeString(dist.resolve("index.html"), shell);
    PagesProperties properties =
        new PagesProperties("https://helper2.ai", false, index.toUri().toString(), 50_000);
    return new PageHtml(
        new PageTemplate(new DefaultResourceLoader(), properties),
        JsonMapper.builder().build(),
        properties);
  }

  private static ResourceView hostile() {
    return new ResourceView(
        ID,
        HOSTILE,
        ResourceType.LIBRARY,
        HOSTILE,
        ValueOrigin.SOURCE_PROVIDED,
        URI.create("https://github.com/acme/x"),
        HOSTILE,
        null,
        Set.of(),
        null,
        null,
        null,
        null,
        List.of(
            new Link(HOSTILE, URI.create("https://github.com/acme/x")),
            new Link("bad", URI.create("javascript:alert(1)"))),
        List.of(new Related(UUID.randomUUID(), HOSTILE)));
  }

  @Test
  void writesTheHeadIntoTheShellAndTheBodyIntoRoot() throws IOException {
    String page = html(SHELL).render(new Pages("https://helper2.ai").of(PageKind.SOURCES));
    assertThat(page)
        .startsWith("<!doctype html>")
        .contains("<title data-h2-head>Sources: where Helper2.ai searches · Helper2.ai</title>")
        .contains("<meta data-h2-head name=\"robots\" content=\"index, follow\">")
        .contains("<link data-h2-head rel=\"canonical\" href=\"https://helper2.ai/sources\">")
        .contains("<script type=\"module\" src=\"/assets/index.js\"></script>")
        .contains("<div id=\"root\"></div>")
        .doesNotContain("<title>Helper2.ai</title>");
  }

  @Test
  void sourceTextCannotOpenATagOrLeaveTheJsonLd() throws IOException {
    String page = html(SHELL).render(new Pages("https://helper2.ai").resource(hostile()));
    // Only the shell's own script and the JSON-LD scripts open a script element.
    assertThat(Pattern.compile("<script").matcher(page).results().count()).isEqualTo(1 + 2);
    assertThat(page).doesNotContain("<script>alert").doesNotContain("<b>&amp;");
    assertThat(page).doesNotContain("\" onload=\"x\"");
    assertThat(page).doesNotContain("javascript:");
    // Source links point at sites nobody reviewed (web search): they pass no ranking.
    assertThat(page).contains("<a href=\"https://github.com/acme/x\" rel=\"nofollow ugc\">");
    String jsonLd =
        page.substring(
            page.indexOf("application/ld+json\">"),
            page.indexOf("</script>", page.indexOf("application/ld+json")));
    assertThat(jsonLd)
        .contains("\\u003c/script\\u003e")
        .contains("\\u2028")
        .doesNotContain("\u2028");
  }

  @Test
  void theAppsOwnShellHasTheMarkers() throws IOException {
    String shell = Files.readString(Path.of("../frontend/index.html"));
    assertThat(html(shell).render(new Pages("https://helper2.ai").home()))
        .contains("<title data-h2-head>Helper2.ai: the metasearch for the AI universe")
        .contains("<div id=\"root\"></div>");
  }

  @Test
  void aShellWithoutTheMarkersStopsTheStart() {
    assertThatThrownBy(() -> html("<html><head></head><body><div id=\"app\"></div></body></html>"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ADR 0050");
  }

  @Test
  void withoutABuiltAppThereIsNoTemplate() {
    PagesProperties properties =
        new PagesProperties("https://helper2.ai", false, "classpath:/no-such/index.html", 50_000);
    assertThat(new PageTemplate(new DefaultResourceLoader(), properties).present()).isFalse();
  }

  @Test
  void everyRouteOfTheAppIsAPageTheServerKnows() throws IOException {
    String routes = Files.readString(Path.of("../frontend/src/routes.tsx"));
    List<String> paths =
        Pattern.compile("path: '([^'*]+)'").matcher(routes).results().map(m -> m.group(1)).toList();
    assertThat(paths).contains("resource/:id", "sources", "review");
    for (String path : paths) {
      String concrete = path.replace(":id", ID.toString());
      boolean known =
          PageAddress.Companion.of(concrete) != PageAddress.Unknown.INSTANCE
              || PageAddress.Companion.of("admin/" + concrete) != PageAddress.Unknown.INSTANCE;
      assertThat(known)
          .as("routes.tsx path '%s' is a page the server knows (ADR 0050)", path)
          .isTrue();
    }
  }
}
