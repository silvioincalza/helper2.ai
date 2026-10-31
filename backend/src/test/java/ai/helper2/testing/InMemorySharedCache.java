package ai.helper2.testing;

import ai.helper2.platform.cache.SharedCache;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The shared cache of unit tests: one map stands for the table, so two adapters on it stand for two replicas. It keeps
 * each entry's TTL to check it, and never expires an entry.
 */
public final class InMemorySharedCache implements SharedCache {

    private final String name;
    public final Map<String, String> values = new ConcurrentHashMap<>();
    public final Map<String, Duration> ttls = new ConcurrentHashMap<>();
    public boolean down;

    public InMemorySharedCache(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public @Nullable String get(String key) {
        return down ? null : values.get(key);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        if (!down) {
            values.put(key, value);
            ttls.put(key, ttl);
        }
    }
}
