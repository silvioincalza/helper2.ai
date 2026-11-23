package ai.helper2.site.pages.web;

import ai.helper2.site.pages.config.IndexNowKeyProperties;
import ai.helper2.site.pages.config.PagesProperties;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class IndexNowKeyFile {

  private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1));

  private final String key;
  private final boolean noindex;

  IndexNowKeyFile(IndexNowKeyProperties indexNow, PagesProperties pages) {
    this.key = indexNow.key();
    this.noindex = pages.noindex();
  }

  @GetMapping(value = "/{name:[A-Za-z0-9-]{8,128}}.txt", produces = MediaType.TEXT_PLAIN_VALUE)
  ResponseEntity<String> keyFile(@PathVariable String name) {
    if (noindex || key.isEmpty() || !key.equals(name)) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok().cacheControl(CACHE).body(key);
  }
}
