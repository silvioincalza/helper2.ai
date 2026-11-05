package ai.helper2.platform.quota;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Names of the daily quota counters and of the stored web result sets (ADR 0037), and the pseudonym
 * of a client for the rate limits and the guide's daily quota, from {@code H2_QUOTA_KEY_SECRET}.
 *
 * <ul>
 *   <li>Client: {@link #client} is HMAC-SHA256(day key, client), where the day key is
 *       HMAC-SHA256(secret, UTC date). No address is stored, and without the secret the pseudonyms
 *       of two days cannot be linked to each other or to an address. Without {@code
 *       H2_QUOTA_KEY_SECRET} it uses a random key of this process instead: still no address, but
 *       the pseudonyms change on restart and differ between replicas.
 *   <li>Guest: {@code h2:v1:quota:guest:<client pseudonym>}; needs the secret.
 *   <li>Signed-in user: {@code h2:v1:quota:user:<user id>:<UTC date>}.
 *   <li>Global: {@code h2:v1:quota:global:<UTC date>}.
 *   <li>A stored query: HMAC-SHA256(secret, text), the same on every day; a short query cannot be
 *       found again by hashing guesses without the secret.
 * </ul>
 */
public final class QuotaKeys {

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

  /**
   * The client's pseudonym for today (UTC): 64 hex characters that hold no address. Stable within
   * the day, another one the next day.
   */
  public String client(String client) {
    return pseudonym(clientSecret, client);
  }

  public String guest(String client) {
    return PREFIX + "guest:" + pseudonym(secret(), client);
  }

  public String user(UUID userId) {
    return user(userId, today());
  }

  /**
   * Every key of the user that may still be alive: today's and yesterday's (a key lives one day).
   */
  public List<String> userKeys(UUID userId) {
    return List.of(user(userId, today()), user(userId, today().minusDays(1)));
  }

  public String global() {
    return PREFIX + "global:" + today();
  }

  public byte[] queryKey(String text) {
    return hmac(secret(), "query|" + text);
  }

  private static String user(UUID userId, LocalDate day) {
    return PREFIX + "user:" + userId + ":" + day;
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
