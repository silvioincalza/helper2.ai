package ai.helper2.platform.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A model's answer for a search query, shared by every replica, so the cluster pays for one query once (ADR 0005).
 * The key is a hash of its parts: the query text never appears in a key. Disposable: get answers null on a miss or
 * while the store is unavailable, and put then does nothing, so the caller just calls the model.
 */
public interface SharedCache {

    /** The key's namespace: keys are {@code h2:v1:{name}:{sha256}}. */
    String name();

    @Nullable String get(String key);

    void put(String key, String value);

    /** A fixed-length key for these parts (model, prompt version, normalised query…), in this order. */
    default String key(String... parts) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8));
            return "h2:v1:" + name() + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same query typed with other case or spaces is the same entry. */
    static String normalisedQuery(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
