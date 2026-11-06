package ai.helper2.sources.evidence;

import ai.helper2.platform.cache.SharedCache;
import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.ExtraEvidence;
import ai.helper2.scout.ExtraText;
import ai.helper2.scout.GapKind;
import ai.helper2.scout.GapTarget;
import ai.helper2.scout.ReadmeCheck;
import ai.helper2.search.SourceFailure;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * The gap round's text (ADR 0043): a GitHub repository's README and the licence GitHub states for
 * it, or a Hugging Face model's card and the licence its card data states. Owner and name come from
 * {@link ai.helper2.scout.GapTargets} (already strict); they are passed as URI template variables,
 * so they are encoded and cannot reach another API path. Only the configured hosts are called; the
 * source token never follows a redirect to another host (ObservedHttp).
 *
 * <p>README first (ADR 0056): {@link #check} sends the ETag of the last read ({@code
 * If-None-Match}). GitHub answers 304 when the README did not change, and a 304 does not count on
 * its rate limit. For that, the shared cache ({@code ai_cache}, name {@value #REVISION_CACHE})
 * keeps per repository only the ETag and a hash of the README, for {@link #REVISION_TTL}: never the
 * text.
 */
public class ReadmeEvidence implements ExtraEvidence {

  static final String POLICY = "collector-gap-evidence";

  /** A README past this is not worth reading; the window keeps 4000 characters anyway. */
  static final int MAX_BYTES = 512 * 1024;

  /**
   * "No licence file GitHub could identify" and "a licence GitHub does not know": not a licence.
   */
  private static final Set<String> NOT_A_LICENSE = Set.of("NOASSERTION", "OTHER");

  /** The shared-cache name of the README revisions: ETag and hash, never the text. */
  public static final String REVISION_CACHE = "github-readme-revision";

  /**
   * How long a README's ETag is kept. A check inside it that finds the README unchanged costs no
   * rate limit; after it, one full read.
   */
  static final Duration REVISION_TTL = Duration.ofDays(30);

  private static final int NOT_MODIFIED = 304;

  /**
   * A redirect the transport did not follow (to another host while a token is set) arrives as a
   * 3xx: its body is not the README. A 304 is no redirect: the README did not change.
   */
  private static final RestClient.ResponseSpec.ErrorHandler REDIRECT_REFUSED =
      (request, response) -> {
        if (response.getStatusCode().value() != NOT_MODIFIED) {
          throw new SourceFailure.Unavailable("redirect not followed");
        }
      };

  private final @Nullable RestClient github;
  private final @Nullable RestClient hub;
  private final Resilience resilience;
  private final Clock clock;
  private final @Nullable SharedCache revisions;

  /**
   * @param revisions where the ETag and hash of each README read are kept; null: every check reads
   *     the README in full
   */
  public ReadmeEvidence(
      @Nullable RestClient github,
      @Nullable RestClient hub,
      Resilience resilience,
      Clock clock,
      @Nullable SharedCache revisions) {
    this.github = github;
    this.hub = hub;
    this.resilience = resilience;
    this.clock = clock;
    this.revisions = revisions;
  }

  public static ReadmeEvidence of(
      SourceSettings github,
      SourceSettings hub,
      RestClient.Builder githubBuilder,
      RestClient.Builder hubBuilder,
      Resilience resilience,
      Clock clock,
      @Nullable SharedCache revisions) {
    // Without a token GitHub allows 60 calls an hour: the gap round would spend them on 429s. Same
    // rule as the awesome lists.
    return new ReadmeEvidence(
        github.enabled() && github.hasToken()
            ? HttpClients.gitHubClient(githubBuilder, github)
            : null,
        hub.enabled() ? HttpClients.client(hubBuilder, hub) : null,
        resilience,
        clock,
        revisions);
  }

  @Override
  public @Nullable ExtraText fetch(GapTarget target) {
    return target.getKind() == GapKind.GITHUB_README
        ? github(target, readme(target, null))
        : modelCard(target);
  }

  /**
   * A GitHub README with the ETag of the last read: on a 304 only the revision we kept, without a
   * text. A model card is always read in full.
   */
  @Override
  public @Nullable ReadmeCheck check(GapTarget target) {
    if (target.getKind() != GapKind.GITHUB_README) {
      ExtraText card = modelCard(target);
      return card == null ? null : new ReadmeCheck(card.getRevisionOrHash(), card);
    }
    Revision known = known(target);
    ResponseEntity<String> response = readme(target, known == null ? null : known.etag());
    if (response != null && response.getStatusCode().value() == NOT_MODIFIED) {
      if (known == null) {
        throw new SourceFailure.Unavailable("not modified, but no revision kept");
      }
      return new ReadmeCheck(known.hash(), null);
    }
    ExtraText text = github(target, response);
    return text == null ? null : new ReadmeCheck(text.getRevisionOrHash(), text);
  }

  /** The README response, or null when there is none (no token, or 404). */
  private @Nullable ResponseEntity<String> readme(GapTarget t, @Nullable String etag) {
    if (github == null) {
      return null;
    }
    return notFoundIsNull(() ->
        resilience.call(
            POLICY,
            () ->
                HttpClients.exchange(
                    () ->
                        github
                            .get()
                            .uri("/repos/{owner}/{repo}/readme", t.getOwner(), t.getName())
                            .header("Accept", "application/vnd.github.raw+json")
                            .headers(h -> {
                              if (etag != null) {
                                h.setIfNoneMatch(etag);
                              }
                            })
                            .attributes(ObservedHttp.maxResponseBytes(MAX_BYTES))
                            .retrieve()
                            .onStatus(HttpStatusCode::is3xxRedirection, REDIRECT_REFUSED)
                            .toEntity(String.class),
                    clock)));
  }

  /** The README text of [response] with the licence GitHub states, and its revision kept. */
  private @Nullable ExtraText github(GapTarget t, @Nullable ResponseEntity<String> response) {
    String readme = response == null ? null : response.getBody();
    if (readme == null || readme.isBlank()) {
      return null;
    }
    String revision = hash(readme);
    remember(t, response.getHeaders().getETag(), revision);
    JsonNode repo =
        notFoundIsNull(() ->
            resilience.call(
                POLICY,
                () ->
                    HttpClients.getJson(
                        () ->
                            github
                                .get()
                                .uri("/repos/{owner}/{repo}", t.getOwner(), t.getName())
                                .retrieve()
                                .body(String.class),
                        clock)));
    String license = repo == null ? null : spdx(HttpClients.text(repo.path("license"), "spdx_id"));
    // The repository page shows whichever README the repository has (README.md, readme.rst,
    // docs/README.md...).
    return new ExtraText(
        link("https://github.com/{o}/{r}#readme", t), clock.instant(), readme, license, revision);
  }

  /** The ETag and hash of the last read of [t]'s README, or null. */
  private @Nullable Revision known(GapTarget t) {
    if (revisions == null) {
      return null;
    }
    String kept = revisions.get(revisionKey(t));
    int split = kept == null ? -1 : kept.indexOf('\n');
    return split <= 0 ? null : new Revision(kept.substring(0, split), kept.substring(split + 1));
  }

  private void remember(GapTarget t, @Nullable String etag, String revision) {
    if (revisions != null && etag != null && !etag.isBlank() && etag.indexOf('\n') < 0) {
      revisions.put(revisionKey(t), etag + "\n" + revision, REVISION_TTL);
    }
  }

  private String revisionKey(GapTarget t) {
    return revisions.key(
        "github", t.getOwner().toLowerCase(Locale.ROOT), t.getName().toLowerCase(Locale.ROOT));
  }

  /** What is kept of one README read: the ETag GitHub sent and a hash of the text. */
  private record Revision(String etag, String hash) {}

  /** First 16 bytes of the SHA-256 of [text], hex: the README's revision in the fingerprint. */
  static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)),
              0,
              16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private @Nullable ExtraText modelCard(GapTarget t) {
    if (hub == null) {
      return null;
    }
    String card =
        notFoundIsNull(() ->
            resilience.call(
                POLICY,
                () ->
                    HttpClients.getText(
                        () ->
                            hub.get()
                                .uri(
                                    "/{owner}/{model}/raw/main/README.md",
                                    t.getOwner(),
                                    t.getName())
                                .attributes(ObservedHttp.maxResponseBytes(MAX_BYTES))
                                .retrieve()
                                .onStatus(HttpStatusCode::is3xxRedirection, REDIRECT_REFUSED)
                                .body(String.class),
                        clock)));
    if (card == null || card.isBlank()) {
      return null;
    }
    return new ExtraText(
        link("https://huggingface.co/{o}/{r}/blob/main/README.md", t),
        clock.instant(),
        card,
        cardLicense(card));
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
      // Only the top-level key: an indented "license:" belongs to something nested (a dataset, a
      // base model).
      if (line.toLowerCase(Locale.ROOT).startsWith("license:")) {
        return spdx(line.substring("license:".length()).strip().replace("\"", "").replace("'", ""));
      }
    }
    return null;
  }

  private static @Nullable String spdx(@Nullable String id) {
    if (id == null
        || id.isBlank()
        || NOT_A_LICENSE.contains(id.toUpperCase(Locale.ROOT))
        || !id.matches("[A-Za-z0-9.+-]{2,40}")) {
      return null;
    }
    return id;
  }

  /**
   * A page a person can open: the link the evidence shows. Owner and name are already strict
   * (GapTargets).
   */
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
