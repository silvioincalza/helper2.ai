package ai.helper2.platform.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A model's answer shared by every replica, so the cluster pays for one question once (ADR 0005). The key is a hash of
 * its parts: the query text never appears in a key. Disposable: get answers null on a miss or while the store is
 * unavailable or slow, and put then does nothing, so the caller just calls the model.
 */
public interface SharedCache {

    /** The cache an entry belongs to ({@code translation}, {@code query-vector}, {@code tool-guide}). */
    String name();

    @Nullable String get(String key);

    /** Keeps the value for {@code ttl}; a read after that is a miss. */
    void put(String key, String value, Duration ttl);

    /** A fixed-length key (SHA-256, hex) for these parts (model, prompt version, normalised query…), in this order. */
    default String key(String... parts) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same query typed with other case or spaces is the same entry. */
    static String normalisedQuery(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
