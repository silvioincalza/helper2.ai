package ai.helper2.pages.web;

import ai.helper2.pages.config.PagesProperties;
import ai.helper2.pages.usecase.ListSitemap;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * robots.txt and sitemap.xml (ADR 0050 §6). With noindex on (staging) robots.txt allows all: a
 * crawler must fetch a page to see its X-Robots-Tag; and there is no sitemap.
 */
@RestController
class CrawlerFiles {

  private final ListSitemap sitemap;
  private final PagesProperties properties;

  CrawlerFiles(ListSitemap sitemap, PagesProperties properties) {
    this.sitemap = sitemap;
    this.properties = properties;
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
    return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1))).body(body);
  }

  @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
  ResponseEntity<String> sitemap() {
    if (properties.noindex()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
        .body(sitemap.xml());
  }
}
