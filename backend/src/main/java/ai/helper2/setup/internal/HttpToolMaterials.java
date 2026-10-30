package ai.helper2.setup.internal;

import ai.helper2.setup.Material;
import ai.helper2.setup.MaterialKind;
import ai.helper2.setup.ToolMaterials;
import ai.helper2.setup.ToolRef;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads a resource's own documents from fixed hosts only, by identifiers parsed from its source records:
 * the GitHub README (REST API, raw), the npm package README (jsDelivr, which serves the published file), the
 * Hugging Face model or Space card. Bodies are capped at {@link #MAX_BYTES}; a document that cannot be read is
 * skipped. Every fetch takes one unit of the {@link HourlyFetchBudget}. Results are kept for the cache TTL, up to a
 * total document length; an answer cut short by the budget is not kept, so the next hour reads it whole.
 */
class HttpToolMaterials implements ToolMaterials {

    static final int MAX_BYTES = 512 * 1024;
    private static final Logger log = LoggerFactory.getLogger(HttpToolMaterials.class);

    /** Base URLs of the fixed hosts, and the tokens the sources use there (empty for none). */
    record Hosts(String github, String npm, String hf, String githubToken, String hfToken) {
    }

    /** How long documents are kept, and the most text (in characters) kept at once. */
    record CacheSettings(Duration ttl, long maxChars) {
    }

    private final RestClient http;
    private final String github;
    private final String npm;
    private final String hf;
    private final String githubToken;
    private final String hfToken;
    private final Clock clock;
    private final HourlyFetchBudget budget;
    private final Cache<ToolRef, List<Material>> cache;

    HttpToolMaterials(RestClient.Builder builder, Hosts hosts, Clock clock, HourlyFetchBudget budget, CacheSettings settings) {
        this.http = builder.build();
        this.github = strip(hosts.github());
        this.npm = strip(hosts.npm());
        this.hf = strip(hosts.hf());
        this.githubToken = hosts.githubToken() == null ? "" : hosts.githubToken().strip();
        this.hfToken = hosts.hfToken() == null ? "" : hosts.hfToken().strip();
        this.clock = clock;
        this.budget = budget;
        this.cache = Caffeine.newBuilder()
            .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
            .expireAfterWrite(settings.ttl())
            .maximumWeight(settings.maxChars())
            .weigher((ToolRef ref, List<Material> docs) -> docs.stream().mapToInt(m -> m.getText().length()).sum())
            .executor(Runnable::run)
            .build();
    }

    @Override
    public List<Material> load(ToolRef ref) {
        List<Material> cached = cache.getIfPresent(ref);
        if (cached != null) {
            return cached;
        }
        Instant now = clock.instant();
        Fetches out = new Fetches();
        if (ref.getGithubRepo() != null) {
            String[] p = ref.getGithubRepo().split("/", 2);
            add(out, "readme", MaterialKind.README, URI.create("https://github.com/" + ref.getGithubRepo()),
                URI.create(github + "/repos/" + p[0] + "/" + p[1] + "/readme"), "application/vnd.github.raw+json", githubToken, now);
        }
        if (ref.getNpmPackage() != null && out.materials.isEmpty()) {
            add(out, "npm", MaterialKind.PACKAGE_README, URI.create("https://www.npmjs.com/package/" + ref.getNpmPackage()),
                URI.create(npm + "/npm/" + ref.getNpmPackage() + "/README.md"), "text/plain", "", now);
        }
        if (ref.getHfModel() != null) {
            add(out, "card", MaterialKind.MODEL_CARD, URI.create("https://huggingface.co/" + ref.getHfModel()),
                URI.create(hf + "/" + ref.getHfModel() + "/raw/main/README.md"), "text/plain", hfToken, now);
        }
        if (ref.getHfSpace() != null) {
            add(out, "card", MaterialKind.MODEL_CARD, URI.create("https://huggingface.co/spaces/" + ref.getHfSpace()),
                URI.create(hf + "/spaces/" + ref.getHfSpace() + "/raw/main/README.md"), "text/plain", hfToken, now);
        }
        List<Material> materials = List.copyOf(out.materials);
        if (!out.refused) {
            cache.put(ref, materials);
        }
        return materials;
    }

    /** Total length of the documents kept now; for tests. */
    long cachedChars() {
        cache.cleanUp();
        return cache.policy().eviction().orElseThrow().weightedSize().orElse(0);
    }

    /** What one load read, and whether the budget turned a fetch down. */
    private static final class Fetches {
        final List<Material> materials = new ArrayList<>();
        boolean refused;
    }

    private void add(Fetches out, String key, MaterialKind kind, URI page, URI fetch, String accept, String token, Instant now) {
        if (!budget.tryAcquire()) {
            out.refused = true;
            log.debug("tool document {} not read: hourly fetch budget used up", fetch.getHost());
            return;
        }
        String text = fetch(fetch, accept, token);
        if (text != null && !text.isBlank()) {
            out.materials.add(new Material(key, kind, page, now, text));
        }
    }

    private @Nullable String fetch(URI uri, String accept, String token) {
        try {
            return http.get().uri(uri).headers(h -> {
                h.set("Accept", accept);
                if (!token.isEmpty()) {
                    h.setBearerAuth(token);
                }
            }).exchange((req, res) -> {
                if (!res.getStatusCode().is2xxSuccessful()) {
                    return null;
                }
                try (InputStream in = res.getBody()) {
                    byte[] bytes = in.readNBytes(MAX_BYTES);
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            });
        } catch (RestClientException e) {
            log.debug("tool document {} not read: {}", uri.getHost(), e.getClass().getSimpleName());
            return null;
        }
    }

    private static String strip(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
