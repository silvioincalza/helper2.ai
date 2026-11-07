package ai.helper2.pages.internal;

import ai.helper2.pages.config.PagesProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * The built app shell (index.html), read once at start-up and cut where the pages write (ADR 0029
 * (part was 0050) §1). In the cluster it is the web image's shell of the same version, copied into
 * the pod by an init container (ADR 0032). Without a shell (development: Vite serves the UI) there
 * is no template and the pages are not served. A shell without the markers stops the start-up: the
 * pages would silently lose their head.
 */
@Component
public class PageTemplate {

  static final String ROOT = "<div id=\"root\"></div>";
  private static final Pattern TITLE = Pattern.compile("<title>[^<]*</title>");
  private static final String HEAD_END = "</head>";

  /**
   * The shell in four parts: before the title, between title and head end, up to root, the rest.
   */
  record Parts(String beforeTitle, String afterTitle, String beforeRoot, String afterRoot) {}

  private final Optional<Parts> parts;

  public PageTemplate(ResourceLoader loader, PagesProperties properties) {
    this.parts = read(loader.getResource(properties.template())).map(PageTemplate::cut);
  }

  public boolean present() {
    return parts.isPresent();
  }

  /**
   * The shell with [title], [head] (tags before the head's end) and [body] (inside #root). Every
   * script and module preload of the shell itself carries [nonce] (ADR 0030); the filled parts do
   * not, so a script that slipped through the escaping would still be refused by the CSP.
   */
  public String fill(String title, String head, String body, String nonce) {
    Parts p = parts.orElseThrow(() -> new IllegalStateException("no built app"));
    return withNonce(p.beforeTitle(), nonce)
        + title
        + withNonce(p.afterTitle(), nonce)
        + head
        + withNonce(p.beforeRoot(), nonce)
        + body
        + withNonce(p.afterRoot(), nonce);
  }

  public static String withNonce(String shell, String nonce) {
    String attribute = " nonce=\"" + nonce + "\"";
    return shell
        .replace("<script", "<script" + attribute)
        .replace("<link rel=\"modulepreload\"", "<link" + attribute + " rel=\"modulepreload\"");
  }

  static Parts cut(String html) {
    Matcher title = TITLE.matcher(html);
    int headEnd = html.indexOf(HEAD_END);
    int root = html.indexOf(ROOT);
    if (!title.find() || headEnd < title.end() || root < headEnd) {
      throw new IllegalStateException(
          "The built index.html has no <title>, </head> or " + ROOT + " in that order (ADR 0029)");
    }
    String rootOpen = "<div id=\"root\">";
    return new Parts(
        html.substring(0, title.start()),
        html.substring(title.end(), headEnd),
        html.substring(headEnd, root) + rootOpen,
        html.substring(root + rootOpen.length()));
  }

  private static Optional<String> read(Resource resource) {
    if (!resource.exists()) {
      return Optional.empty();
    }
    try (InputStream in = resource.getInputStream()) {
      return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
