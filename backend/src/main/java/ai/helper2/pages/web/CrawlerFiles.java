package ai.helper2.pages.web;

import ai.helper2.monetisation.Monetisation;
import ai.helper2.pages.config.PagesProperties;
import ai.helper2.pages.usecase.ListSitemap;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * robots.txt, sitemap.xml (ADR 0050 §6) and ads.txt (ADR 0059). With noindex on (staging)
 * robots.txt allows all: a crawler must fetch a page to see its X-Robots-Tag; and there is no
 * sitemap.
 */
@RestController
class CrawlerFiles {

  private final ListSitemap sitemap;
  private final PagesProperties properties;
  private final Monetisation monetisation;

  CrawlerFiles(ListSitemap sitemap, PagesProperties properties, Monetisation monetisation) {
    this.sitemap = sitemap;
    this.properties = properties;
    this.monetisation = monetisation;
  }

  /**
   * ads.txt (ADR 0059): the sellers authorised to sell ads on this site, here Google for the
   * AdSense publisher. 404 while AdSense is off.
   */
  @GetMapping(value = "/ads.txt", produces = MediaType.TEXT_PLAIN_VALUE)
  ResponseEntity<String> adsTxt() {
    return monetisation
        .adsTxt()
        .map(line ->
            ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(line + "\n"))
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
