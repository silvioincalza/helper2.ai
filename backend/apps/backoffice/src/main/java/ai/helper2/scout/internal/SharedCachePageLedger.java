package ai.helper2.scout.internal;

import ai.helper2.platform.cache.SharedCache;
import ai.helper2.scout.PageLedger;
import java.net.URI;
import java.time.Duration;

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
