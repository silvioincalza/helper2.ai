package ai.helper2.sources.evidence;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.ExtraEvidence;
import ai.helper2.scout.ExtraText;
import ai.helper2.scout.GapKind;
import ai.helper2.scout.GapTarget;
import ai.helper2.search.SourceFailure;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * The gap round's text (ADR 0043): a GitHub repository's README and the licence GitHub states for it, or a Hugging
 * Face model's card and the licence its card data states. Owner and name come from {@link ai.helper2.scout.GapTargets}
 * (already strict); they are passed as URI template variables, so they are encoded and cannot reach another API path.
 * Only the configured hosts are called; the source token never follows a redirect to another host (ObservedHttp).
 */
public class ReadmeEvidence implements ExtraEvidence {

    static final String POLICY = "collector-gap-evidence";

    /** A README past this is not worth reading; the window keeps 4000 characters anyway. */
    static final int MAX_BYTES = 512 * 1024;

    /** "No licence file GitHub could identify" and "a licence GitHub does not know": not a licence. */
    private static final Set<String> NOT_A_LICENSE = Set.of("NOASSERTION", "OTHER");

    private final @Nullable RestClient github;
    private final @Nullable RestClient hub;
    private final Resilience resilience;
    private final Clock clock;

    public ReadmeEvidence(@Nullable RestClient github, @Nullable RestClient hub, Resilience resilience, Clock clock) {
        this.github = github;
        this.hub = hub;
        this.resilience = resilience;
        this.clock = clock;
    }

    public static ReadmeEvidence of(SourceSettings github, SourceSettings hub, RestClient.Builder githubBuilder,
                                    RestClient.Builder hubBuilder, Resilience resilience, Clock clock) {
        return new ReadmeEvidence(github.enabled() ? HttpClients.gitHubClient(githubBuilder, github) : null,
            hub.enabled() ? HttpClients.client(hubBuilder, hub) : null, resilience, clock);
    }

    @Override
    public @Nullable ExtraText fetch(GapTarget target) {
        return target.getKind() == GapKind.GITHUB_README ? github(target) : modelCard(target);
    }

    private @Nullable ExtraText github(GapTarget t) {
        if (github == null) {
            return null;
        }
        String readme = notFoundIsNull(() -> resilience.call(POLICY, () -> HttpClients.getText(() -> github.get()
            .uri("/repos/{owner}/{repo}/readme", t.getOwner(), t.getName())
            .header("Accept", "application/vnd.github.raw+json")
            .attributes(ObservedHttp.maxResponseBytes(MAX_BYTES))
            .retrieve().body(String.class), clock)));
        if (readme == null || readme.isBlank()) {
            return null;
        }
        JsonNode repo = notFoundIsNull(() -> resilience.call(POLICY, () -> HttpClients.getJson(() -> github.get()
            .uri("/repos/{owner}/{repo}", t.getOwner(), t.getName())
            .retrieve().body(String.class), clock)));
        String license = repo == null ? null : spdx(HttpClients.text(repo.path("license"), "spdx_id"));
        return new ExtraText(link("https://github.com/{o}/{r}/blob/HEAD/README.md", t), clock.instant(), readme, license);
    }

    private @Nullable ExtraText modelCard(GapTarget t) {
        if (hub == null) {
            return null;
        }
        String card = notFoundIsNull(() -> resilience.call(POLICY, () -> HttpClients.getText(() -> hub.get()
            .uri("/{owner}/{model}/raw/main/README.md", t.getOwner(), t.getName())
            .attributes(ObservedHttp.maxResponseBytes(MAX_BYTES))
            .retrieve().body(String.class), clock)));
        if (card == null || card.isBlank()) {
            return null;
        }
        return new ExtraText(link("https://huggingface.co/{o}/{r}/blob/main/README.md", t), clock.instant(), card, cardLicense(card));
    }

    /** The {@code license:} line of a model card's YAML front matter, when it is an SPDX-style id. */
    static @Nullable String cardLicense(String card) {
        if (!card.startsWith("---")) {
            return null;
        }
        int end = card.indexOf("\n---", 3);
        if (end < 0) {
            return null;
        }
        for (String line : card.substring(3, end).split("\n")) {
            String l = line.strip();
            if (l.toLowerCase(Locale.ROOT).startsWith("license:")) {
                return spdx(l.substring("license:".length()).strip().replace("\"", "").replace("'", ""));
            }
        }
        return null;
    }

    private static @Nullable String spdx(@Nullable String id) {
        if (id == null || id.isBlank() || NOT_A_LICENSE.contains(id.toUpperCase(Locale.ROOT)) || !id.matches("[A-Za-z0-9.+-]{2,40}")) {
            return null;
        }
        return id;
    }

    /** A page a person can open: the link the evidence shows. Owner and name are already strict (GapTargets). */
    private static URI link(String template, GapTarget t) {
        return URI.create(template.replace("{o}", t.getOwner()).replace("{r}", t.getName()));
    }

    /** A README or repository that is gone is no text, not a failure. */
    private static <T> @Nullable T notFoundIsNull(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (SourceFailure.Unavailable e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                return null;
            }
            throw e;
        }
    }
}
