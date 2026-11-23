package ai.helper2.sources.github.scout;

import ai.helper2.scout.PageReader;
import ai.helper2.scout.ReadPage;
import ai.helper2.search.SourceFailure;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

final class WebPageReader implements ListReader {

  private final PageReader reader;

  WebPageReader(PageReader reader) {
    this.reader = reader;
  }

  boolean available() {
    return reader.getAvailable();
  }

  @Override
  public String markdown(String address) {
    URI asked = URI.create(address);
    List<ReadPage> pages = reader.extract(List.of(asked));
    if (pages.isEmpty() || pages.getFirst().getRaw().isBlank()) {
      throw new SourceFailure.Unavailable("the page has no readable text");
    }
    ReadPage page = pages.getFirst();
    if (!site(asked.getHost()).equals(site(hostOf(page.getUrl())))) {
      throw new SourceFailure.Unavailable("the page answers from another host");
    }
    return page.getRaw();
  }

  private static @Nullable String hostOf(String url) {
    try {
      return URI.create(url.strip()).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String site(@Nullable String host) {
    String name = host == null ? "" : host.toLowerCase(Locale.ROOT);
    return name.startsWith("www.") ? name.substring(4) : name;
  }
}
