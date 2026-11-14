package ai.helper2.site.pages.web;

import ai.helper2.site.monetisation.Monetisation;
import ai.helper2.site.pages.config.PagesProperties;
import ai.helper2.site.pages.usecase.ListSitemap;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CrawlerFiles {

  private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1));

  private final ListSitemap sitemap;
  private final PagesProperties properties;
  private final Monetisation monetisation;

  CrawlerFiles(ListSitemap sitemap, PagesProperties properties, Monetisation monetisation) {
    this.sitemap = sitemap;
    this.properties = properties;
    this.monetisation = monetisation;
  }

  @GetMapping("/index.html")
  ResponseEntity<Void> indexHtml() {
    return ResponseEntity.status(301).location(java.net.URI.create("/")).build();
  }

  @GetMapping(value = "/ads.txt", produces = MediaType.TEXT_PLAIN_VALUE)
  ResponseEntity<String> adsTxt() {
    return monetisation
        .adsTxt()
        .map(line -> ResponseEntity.ok().cacheControl(CACHE).body(line + "\n"))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
  ResponseEntity<String> robots() {
    String body =
        properties.noindex()
            ? "User-agent: *\nAllow: /\n"
            : """
            User-agent: *
            Disallow: /oauth2/
            Disallow: /login
            Disallow: /logout
            Disallow: /admin
            Allow: /

            Sitemap: %s/sitemap.xml
            """
                .formatted(properties.publicBaseUrl());
    return ResponseEntity.ok().cacheControl(CACHE).body(body);
  }

  @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
  ResponseEntity<String> sitemap() {
    if (properties.noindex()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok().cacheControl(CACHE).body(sitemap.xml());
  }
}
