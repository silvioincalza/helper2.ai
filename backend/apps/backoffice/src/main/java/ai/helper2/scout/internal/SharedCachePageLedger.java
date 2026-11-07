package ai.helper2.scout.internal;

import ai.helper2.platform.cache.SharedCache;
import ai.helper2.scout.PageLedger;
import java.net.URI;
import java.time.Duration;

/**
 * The pages the evidence ladder read lately (ADR 0056), in the shared cache table: the key is a
 * hash of the URL, the value a hash of the page text. Never the text itself. A page read once is
 * not read, or paid for, again until the entry expires, also after a restart. The cache is
 * disposable: when it cannot answer, the page counts as not seen and may be read again.
 */
final class SharedCachePageLedger implements PageLedger {

  static final String CACHE = "scout-page-read";

  private final SharedCache cache;
  private final Duration ttl;

  SharedCachePageLedger(SharedCache cache, Duration ttl) {
    this.cache = cache;
    this.ttl = ttl;
  }

  @Override
  public boolean seen(URI url) {
    return cache.get(cache.key(url.toString())) != null;
  }

  @Override
  public void record(URI url, String contentHash) {
    cache.put(cache.key(url.toString()), contentHash, ttl);
  }
}
