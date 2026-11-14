package ai.helper2.platform.quota;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class QuotaKeys {
  public static final String CREDITS = "credits";

  public static final String SCOUT_CREDITS = "scout-credits";

  private static final String PREFIX = "h2:v1:quota:";

  private final byte[] secret;
  private final byte[] clientSecret;
  private final Clock clock;

  public QuotaKeys(String secret, Clock clock) {
    this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    this.clientSecret = hasSecret() ? this.secret : randomKey();
    this.clock = clock;
  }

  public boolean hasSecret() {
    return secret.length > 0;
  }

  public String client(String client) {
    return pseudonym(clientSecret, client);
  }

  public String guest(String client) {
    return PREFIX + "guest:" + pseudonym(secret(), client);
  }

  public String global() {
    return PREFIX + "global:" + today();
  }

  public byte[] queryKey(String text) {
    return hmac(secret(), "query|" + text);
  }

  private String pseudonym(byte[] key, String client) {
    byte[] dayKey = hmac(key, "day|" + today());
    return HexFormat.of().formatHex(hmac(dayKey, client));
  }

  private static byte[] randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }

  private byte[] secret() {
    if (!hasSecret()) {
      throw new IllegalStateException("H2_QUOTA_KEY_SECRET is not set");
    }
    return secret;
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private static byte[] hmac(byte[] key, String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
