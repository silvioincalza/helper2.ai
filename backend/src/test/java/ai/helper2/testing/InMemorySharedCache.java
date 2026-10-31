package ai.helper2.testing;

import ai.helper2.platform.cache.SharedCache;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** The shared cache of unit tests: one map stands for Redis, so two adapters on it stand for two replicas. */
public final class InMemorySharedCache implements SharedCache {

    private final String name;
    public final Map<String, String> values = new ConcurrentHashMap<>();
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
    public void put(String key, String value) {
        if (!down) {
            values.put(key, value);
        }
    }
}
