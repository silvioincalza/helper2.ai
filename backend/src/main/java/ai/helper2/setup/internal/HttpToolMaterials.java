package ai.helper2.setup.internal;

import ai.helper2.setup.Material;
import ai.helper2.setup.MaterialKind;
import ai.helper2.setup.ToolMaterials;
import ai.helper2.setup.ToolRef;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads a resource's own documents from fixed hosts only, by identifiers parsed from its source records:
 * the GitHub README (REST API, raw), the npm package README (jsDelivr, which serves the published file), the
 * Hugging Face model or Space card. Bodies are capped at {@link #MAX_BYTES}; a document that cannot be read is
 * skipped. Results are kept for {@link #TTL}, at most {@link #MAX_ENTRIES} resources.
 */
class HttpToolMaterials implements ToolMaterials {

    static final int MAX_BYTES = 512 * 1024;
    static final Duration TTL = Duration.ofHours(1);
    static final int MAX_ENTRIES = 300;
    private static final Logger log = LoggerFactory.getLogger(HttpToolMaterials.class);

    private record Cached(List<Material> materials, Instant expires) {
    }

    private final RestClient http;
    private final String githubToken;
    private final String hfToken;
    private final Clock clock;
    private final Map<ToolRef, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ToolRef, Cached> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private final String github;
    private final String npm;
    private final String hf;

    HttpToolMaterials(RestClient.Builder builder, String githubBase, String npmBase, String hfBase, String githubToken,
                      String hfToken, Clock clock) {
        this.http = builder.build();
        this.github = strip(githubBase);
        this.npm = strip(npmBase);
        this.hf = strip(hfBase);
        this.githubToken = githubToken == null ? "" : githubToken.strip();
        this.hfToken = hfToken == null ? "" : hfToken.strip();
        this.clock = clock;
    }

    @Override
    public List<Material> load(ToolRef ref) {
        Instant now = clock.instant();
        synchronized (cache) {
            Cached c = cache.get(ref);
            if (c != null && now.isBefore(c.expires())) {
                return c.materials();
            }
        }
        List<Material> out = new ArrayList<>();
        if (ref.getGithubRepo() != null) {
            String[] p = ref.getGithubRepo().split("/", 2);
            add(out, "readme", MaterialKind.README, URI.create("https://github.com/" + ref.getGithubRepo()),
                URI.create(github + "/repos/" + p[0] + "/" + p[1] + "/readme"), "application/vnd.github.raw+json", githubToken, now);
        }
        if (ref.getNpmPackage() != null && out.isEmpty()) {
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
        List<Material> materials = List.copyOf(out);
        synchronized (cache) {
            cache.put(ref, new Cached(materials, now.plus(TTL)));
        }
        return materials;
    }

    private void add(List<Material> out, String key, MaterialKind kind, URI page, URI fetch, String accept, String token,
                     Instant now) {
        String text = fetch(fetch, accept, token);
        if (text != null && !text.isBlank()) {
            out.add(new Material(key, kind, page, now, text));
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
