package ai.helper2.platform.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public interface SharedCache {
  String name();

  @Nullable String get(String key);

  void put(String key, String value, Duration ttl);

  default String key(String... parts) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String normalisedQuery(String text) {
    return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
