package ai.helper2.api;

import java.time.Duration;
import org.springframework.http.CacheControl;

public final class PublicCache {

  public static final CacheControl SHORT = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

  public static final CacheControl LONG = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

  private PublicCache() {}
}
