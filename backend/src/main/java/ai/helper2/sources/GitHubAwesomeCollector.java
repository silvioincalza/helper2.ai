package ai.helper2.sources;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * GitHub "awesome" lists about AI, found by topic search. A pass: discover the lists, read each README
 * as a link graph ({@link AwesomeListParser}), fetch facts for the linked repositories with GraphQL
 * (50 per call), let the fast model decide which are AI resources and of what type, and keep those.
 * Bounded per run ({@code maxEntriesPerRun}) and per pass ({@code maxEntriesPerPass}); an unfinished
 * pass resumes from an entry offset. The lists are discovered again on resume, so if their order changed
 * in between a few entries may wait until the next pass; upserts are idempotent, so nothing is duplicated.
 * Needs a GitHub token (GraphQL rejects anonymous calls): without one the collector is disabled.
 */
public class GitHubAwesomeCollector implements Collector {

    static final String ID = "github-awesome";
    /** Circuit breaker and retry of this collector\'s calls (ADR 0033). */
    static final String POLICY = "collector-" + ID;
    private static final Logger log = LoggerFactory.getLogger(GitHubAwesomeCollector.class);
    private static final int GRAPHQL_BATCH = 50;
    private static final int SEARCH_PAGE = 20;

    record Limits(int maxLists, int minListStars, int maxEntriesPerPass, int maxEntriesPerRun, int minRepoStars,
                  int classifyBatch) {
    }

    private final RestClient http;
    private final Clock clock;
    private final Resilience resilience;
    private final RunInterval interval;
    private final List<String> topicQueries;
    private final Limits limits;
    private final RepoClassifier classifier;
    private final RepoClassifier fallback = new RepoClassifier.Heuristic();
    private final SourceDescriptor descriptor;

    public GitHubAwesomeCollector(SourceSettings settings, RestClient.Builder builder, Clock clock, Resilience resilience, RunInterval interval,
                                  List<String> topicQueries, Limits limits, RepoClassifier classifier) {
        this.clock = clock;
        this.resilience = resilience;
        if (settings.enabled()) {
            resilience.require(POLICY);
        }
        this.interval = interval;
        this.topicQueries = topicQueries;
        this.limits = limits;
        this.classifier = classifier;
        this.http = HttpClients.gitHubClient(builder, settings);
        this.descriptor = new SourceDescriptor(ID, "GitHub awesome lists (AI)", SourceMode.INDEXED,
            URI.create("https://github.com/topics/awesome-list"),
            URI.create("https://docs.github.com/en/site-policy/github-terms/github-terms-of-service"),
            "Repository metadata from the GitHub API; lists are used only as links",
            EnumSet.of(ResourceType.LIBRARY, ResourceType.MCP_SERVER, ResourceType.AGENT, ResourceType.PROMPT,
                ResourceType.SKILL, ResourceType.MODEL, ResourceType.DATASET, ResourceType.PRODUCT),
            settings.timeout(), settings.cacheTtl(), settings.enabled() && settings.hasToken(), 25);
    }

    @Override
    public SourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public CollectedBatch collect(String checkpoint, String resumeCursor, int maxItems) {
        Instant startedAt = clock.instant();
        if (!interval.allowsPass(checkpoint, resumeCursor, startedAt)) {
            return CollectedBatch.notDue();
        }
        List<AwesomeListParser.Entry> all = entriesOf(discoverLists());
        int offset = offset(resumeCursor);
        if (offset >= all.size()) {
            return new CollectedBatch(List.of(), null, startedAt.toString());
        }
        int end = Math.min(all.size(), offset + Math.max(1, Math.min(maxItems, limits.maxEntriesPerRun())));
        List<AwesomeListParser.Entry> slice = all.subList(offset, end);
        List<SourceHit> hits = toHits(slice, repoFacts(slice), offset, startedAt);
        log.info("awesome lists: entries {}-{} of {}, {} kept as AI resources (classifier {})",
            offset, end, all.size(), hits.size(), classifier.name());
        return new CollectedBatch(hits, end < all.size() ? String.valueOf(end) : null, startedAt.toString());
    }

    /** Lists by stars, most first. A search query that fails is skipped; rate limits stop the pass. */
    List<String> discoverLists() {
        Map<String, Long> stars = new HashMap<>();
        for (String topics : topicQueries) {
            String q = topics + " fork:false archived:false stars:>=" + limits.minListStars();
            JsonNode root;
            try {
                root = resilience.call(POLICY, () -> HttpClients.getJson(() -> http.get()
                    .uri(u -> u.path("/search/repositories").queryParam("q", q).queryParam("sort", "stars")
                        .queryParam("order", "desc").queryParam("per_page", SEARCH_PAGE).build())
                    .retrieve().body(String.class), clock));
            } catch (SourceFailure.Unavailable e) {
                log.warn("awesome list search '{}' failed: {}", topics, e.getMessage());
                continue;
            }
            for (JsonNode item : root.path("items")) {
                String name = HttpClients.text(item, "full_name");
                // Topic search alone returns prompt sites, security handbooks and apps; awesome lists say so in the name.
                if (name != null && name.toLowerCase(Locale.ROOT).contains("awesome")) {
                    stars.merge(name, item.path("stargazers_count").asLong(0), Math::max);
                }
            }
        }
        return stars.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(limits.maxLists())
            .map(Map.Entry::getKey)
            .toList();
    }

    private List<AwesomeListParser.Entry> entriesOf(List<String> lists) {
        Map<String, AwesomeListParser.Entry> unique = new LinkedHashMap<>();
        for (String list : lists) {
            String readme;
            try {
                readme = readme(list);
            } catch (SourceFailure.Unavailable e) {
                log.warn("awesome list {} README unavailable: {}", list, e.getMessage());
                continue;
            }
            for (AwesomeListParser.Entry e : AwesomeListParser.entries(readme, list)) {
                unique.putIfAbsent(e.fullName().toLowerCase(Locale.ROOT), e);
            }
            if (unique.size() >= limits.maxEntriesPerPass()) {
                break;
            }
        }
        return new ArrayList<>(unique.values()).subList(0, Math.min(unique.size(), limits.maxEntriesPerPass()));
    }

    /** Big awesome lists are long Markdown files; anything past this is not a list worth parsing. */
    static final int README_MAX_BYTES = 5 * 1024 * 1024;

    private String readme(String list) {
        String[] parts = list.split("/", 2);
        return resilience.call(POLICY, () -> HttpClients.getText(() -> http.get()
            .uri("/repos/{owner}/{repo}/readme", parts[0], parts[1])
            .header("Accept", "application/vnd.github.raw+json")
            .attributes(ObservedHttp.maxResponseBytes(README_MAX_BYTES))
            .retrieve().body(String.class), clock));
    }

    /** Facts by lower-case full name. Repositories GitHub cannot resolve (renamed, deleted) are absent. */
    Map<String, JsonNode> repoFacts(List<AwesomeListParser.Entry> entries) {
        Map<String, JsonNode> facts = new HashMap<>();
        for (int from = 0; from < entries.size(); from += GRAPHQL_BATCH) {
            List<AwesomeListParser.Entry> batch = entries.subList(from, Math.min(entries.size(), from + GRAPHQL_BATCH));
            ObjectNode body = JsonNodeFactory.instance.objectNode().put("query", graphQl(batch));
            JsonNode root = resilience.call(POLICY, () -> HttpClients.getJson(() -> http.post().uri("/graphql")
                .header("Content-Type", "application/json").body(body.toString())
                .retrieve().body(String.class), clock));
            for (JsonNode error : root.path("errors")) {
                if ("RATE_LIMITED".equals(error.path("type").asString(""))) {
                    throw new SourceFailure.RateLimited(null);
                }
            }
            JsonNode data = root.path("data");
            if (!data.isObject()) {
                throw new SourceFailure.Unavailable("GraphQL answer without data");
            }
            for (int i = 0; i < batch.size(); i++) {
                JsonNode repo = data.path("r" + i);
                if (repo.isObject()) {
                    facts.put(batch.get(i).fullName().toLowerCase(Locale.ROOT), repo);
                }
            }
        }
        return facts;
    }

    static String graphQl(List<AwesomeListParser.Entry> batch) {
        StringBuilder q = new StringBuilder("query {");
        for (int i = 0; i < batch.size(); i++) {
            String[] parts = batch.get(i).fullName().split("/", 2);
            q.append(" r").append(i).append(": repository(owner: \"").append(parts[0]).append("\", name: \"")
                .append(parts[1]).append("\") { nameWithOwner url description stargazerCount isArchived isFork")
                .append(" createdAt pushedAt licenseInfo { spdxId } repositoryTopics(first: 20) { nodes { topic { name } } } }");
        }
        return q.append(" }").toString();
    }

    private List<SourceHit> toHits(List<AwesomeListParser.Entry> slice, Map<String, JsonNode> facts, int offset,
                                   Instant now) {
        List<RepoClassifier.Facts> candidates = new ArrayList<>();
        Map<String, JsonNode> byId = new HashMap<>();
        Map<String, Integer> rankById = new HashMap<>();
        for (int i = 0; i < slice.size(); i++) {
            AwesomeListParser.Entry entry = slice.get(i);
            JsonNode repo = facts.get(entry.fullName().toLowerCase(Locale.ROOT));
            if (repo == null || repo.path("isArchived").asBoolean(false) || repo.path("isFork").asBoolean(false)
                || repo.path("stargazerCount").asLong(0) < limits.minRepoStars()) {
                continue;
            }
            String id = "r" + candidates.size();
            candidates.add(new RepoClassifier.Facts(id, HttpClients.text(repo, "nameWithOwner"),
                HttpClients.text(repo, "description"), topics(repo), entry.section()));
            byId.put(id, repo);
            rankById.put(id, offset + i);
        }
        Map<String, RepoClassifier.Verdict> verdicts = classify(candidates);
        List<SourceHit> hits = new ArrayList<>();
        for (RepoClassifier.Facts f : candidates) {
            RepoClassifier.Verdict v = verdicts.get(f.id());
            if (v != null && v.ai()) {
                SourceHit hit = toHit(byId.get(f.id()), v.type(), rankById.get(f.id()), now);
                if (hit != null) {
                    hits.add(hit);
                }
            }
        }
        hits.sort(Comparator.comparingInt(SourceHit::getRank));
        return hits;
    }

    /** Model first, in batches; anything it did not answer (an error, a missing id) goes to the heuristic. */
    private Map<String, RepoClassifier.Verdict> classify(List<RepoClassifier.Facts> candidates) {
        Map<String, RepoClassifier.Verdict> verdicts = new HashMap<>();
        int fallbacks = 0;
        for (int from = 0; from < candidates.size(); from += limits.classifyBatch()) {
            List<RepoClassifier.Facts> batch = candidates.subList(from, Math.min(candidates.size(), from + limits.classifyBatch()));
            Map<String, RepoClassifier.Verdict> answered = classifyOrEmpty(batch);
            verdicts.putAll(answered);
            List<RepoClassifier.Facts> missing = batch.stream().filter(f -> !answered.containsKey(f.id())).toList();
            if (!missing.isEmpty() && classifier != fallback) {
                fallbacks += missing.size();
                verdicts.putAll(fallback.classify(missing));
            }
        }
        if (fallbacks > 0) {
            log.warn("awesome list classifier {}: {} of {} repositories classified by the heuristic instead",
                classifier.name(), fallbacks, candidates.size());
        }
        return verdicts;
    }

    private Map<String, RepoClassifier.Verdict> classifyOrEmpty(List<RepoClassifier.Facts> batch) {
        try {
            return classifier.classify(batch);
        } catch (RuntimeException e) {
            log.warn("awesome list classifier {} failed: {}", classifier.name(), e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static List<String> topics(JsonNode repo) {
        List<String> topics = new ArrayList<>();
        repo.path("repositoryTopics").path("nodes").forEach(n -> {
            String t = HttpClients.text(n.path("topic"), "name");
            if (t != null) {
                topics.add(t);
            }
        });
        return topics;
    }

    private static SourceHit toHit(JsonNode repo, ResourceType type, int rank, Instant now) {
        String name = HttpClients.text(repo, "nameWithOwner");
        String url = HttpClients.text(repo, "url");
        CanonicalUrl canonical = url == null ? null : CanonicalUrl.of(url);
        if (name == null || canonical == null) {
            return null;
        }
        String spdx = HttpClients.text(repo.path("licenseInfo"), "spdxId");
        String license = spdx == null ? null : ResultMerger.normalizeLicense(spdx);
        Set<String> tags = new LinkedHashSet<>(topics(repo));
        Audience audience = type == ResourceType.PRODUCT ? Audience.USE_AI : Audience.BUILD_WITH_AI;
        return new SourceHit(ID, name, URI.create(url), canonical, null, name, HttpClients.text(repo, "description"),
            ValueOrigin.SOURCE_PROVIDED, type, audience, null, Set.of(), tags, license, null, Set.of(),
            Classification.openSource(license),
            DateFact.fromSource(HttpClients.instant(repo, "createdAt"), "github", now),
            DateFact.fromSource(HttpClients.instant(repo, "pushedAt"), "github", now),
            List.of(new PopularityMetric(PopularityKind.GITHUB_STARS, repo.path("stargazerCount").asLong(), "github", now)),
            now, rank);
    }

    private static int offset(String cursor) {
        if (cursor == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
