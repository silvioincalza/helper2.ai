package ai.helper2.scout.internal;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.OfficialPage;
import ai.helper2.scout.PageTextCache;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The cleaned text of a product's pages (ADR 0023), in Redis only, under a SHA-256 of the accepted
 * page's URL, for at most {@link #MAX_TTL}. Only so that a retry within the day costs no Tavily
 * credit. The text is what {@code UntrustedPageText} left (no hidden text, emails and phone numbers
 * masked, cut to the window), never the raw page. Disposable: when Redis cannot answer, the pages
 * are read again; the text is never logged.
 */
final class RedisPageTextCache implements PageTextCache {

  static final String PREFIX = "h2:v1:scout:page-text:";
  static final Duration MAX_TTL = Duration.ofDays(1);
  private static final String POLICY = "redis";

  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final Resilience resilience;
  private final Duration ttl;

  RedisPageTextCache(
      StringRedisTemplate redis, ObjectMapper json, Resilience resilience, Duration ttl) {
    if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException("page text TTL must be more than 0 and at most 1 day");
    }
    this.redis = redis;
    this.json = json;
    this.resilience = resilience;
    this.ttl = ttl;
  }

  static String key(URI target) {
    try {
      return PREFIX
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(target.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public @Nullable List<OfficialPage> get(URI target) {
    try {
      String raw = resilience.call(POLICY, () -> redis.opsForValue().get(key(target)));
      if (raw == null) {
        return null;
      }
      List<OfficialPage> pages = new ArrayList<>();
      for (JsonNode p : json.readTree(raw)) {
        pages.add(
            new OfficialPage(
                URI.create(p.path("url").asString()),
                Instant.parse(p.path("retrievedAt").asString()),
                p.path("text").asString(),
                p.path("startHost").asBoolean()));
      }
      return pages;
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public void put(URI target, List<OfficialPage> pages) {
    try {
      List<Map<String, Object>> out = new ArrayList<>();
      for (OfficialPage p : pages) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", p.getUrl().toString());
        m.put("retrievedAt", p.getRetrievedAt().toString());
        m.put("text", p.getText());
        m.put("startHost", p.getStartHost());
        out.add(m);
      }
      String encoded = json.writeValueAsString(out);
      resilience.run(POLICY, () -> redis.opsForValue().set(key(target), encoded, ttl));
    } catch (RuntimeException e) {
      // Disposable: the next attempt reads the pages again.
    }
  }
}
