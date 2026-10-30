package ai.helper2.platform.quota;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Names of the daily quota counters and of the stored web result sets (ADR 0037), from {@code H2_QUOTA_KEY_SECRET}.
 *
 * <ul>
 *   <li>Guest: {@code h2:v1:quota:guest:<HMAC-SHA256(day key, client)>}, where the day key is
 *       HMAC-SHA256(secret, UTC date). No address is stored, and without the secret the keys of two days cannot be
 *       linked to each other or to an address.</li>
 *   <li>Signed-in user: {@code h2:v1:quota:user:<user id>:<UTC date>}.</li>
 *   <li>Global: {@code h2:v1:quota:global:<UTC date>}.</li>
 *   <li>A stored query: HMAC-SHA256(secret, text), the same on every day; a short query cannot be found again by
 *       hashing guesses without the secret.</li>
 * </ul>
 */
public final class QuotaKeys {

    private static final String PREFIX = "h2:v1:quota:";

    private final byte[] secret;
    private final Clock clock;

    public QuotaKeys(String secret, Clock clock) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public boolean hasSecret() {
        return secret.length > 0;
    }

    public String guest(String client) {
        byte[] dayKey = hmac(secret(), "day|" + today());
        return PREFIX + "guest:" + HexFormat.of().formatHex(hmac(dayKey, client));
    }

    public String user(UUID userId) {
        return user(userId, today());
    }

    /** Every key of the user that may still be alive: today's and yesterday's (a key lives one day). */
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
