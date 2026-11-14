package ai.helper2.sources.mcp;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.search.Vitality;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class McpRegistryCollector implements Collector {

  private static final String META = "io.modelcontextprotocol.registry/official";

  static final String POLICY = "collector-mcp-registry";

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final String baseUrl;
  private final SourceDescriptor descriptor;
  private final RunInterval interval;
  private final int itemsPerRun;

  public McpRegistryCollector(
      SourceSettings settings, RestClient.Builder builder, Clock clock, Resilience resilience) {
    this(settings, builder, clock, resilience, new RunInterval(Duration.ZERO));
  }

  public McpRegistryCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval) {
    this(settings, builder, clock, resilience, interval, 0);
  }

  public McpRegistryCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      int itemsPerRun) {
    this.itemsPerRun = itemsPerRun;
    this.clock = clock;
    this.resilience = resilience;
    if (settings.enabled()) {
      resilience.require(POLICY);
    }
    this.interval = interval;
    this.baseUrl = settings.baseUrl();
    this.http = builder.baseUrl(settings.baseUrl()).build();
    this.descriptor = CollectorDescriptors.mcpRegistry(settings);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public CollectedBatch collect(String checkpoint, String resumeCursor, int runBudget) {
    int maxItems = Math.max(runBudget, itemsPerRun);
    Instant startedAt = clock.instant();
    if (!interval.allowsPass(checkpoint, resumeCursor, startedAt)) {
      return CollectedBatch.notDue();
    }
    Map<String, SourceHit> latest = new LinkedHashMap<>();
    Set<String> withdrawn = new LinkedHashSet<>();
    String cursor = resumeCursor;
    int pages = 0;
    do {
      String c = cursor;
      JsonNode root =
          resilience.call(
              POLICY,
              () ->
                  HttpClients.getJson(
                      () ->
                          http.get()
                              .uri(u -> {
                                var b =
                                    u.path("/v0.1/servers")
                                        .queryParam("limit", 100)
                                        .queryParam("version", "latest");
                                if (checkpoint != null) {
                                  b = b.queryParam("updated_since", checkpoint);
                                }
                                if (c != null) {
                                  b = b.queryParam("cursor", c);
                                }
                                return b.build();
                              })
                              .retrieve()
                              .body(String.class),
                      clock));
      for (JsonNode entry : root.path("servers")) {
        JsonNode meta = entry.path("_meta").path(META);
        if (!meta.path("isLatest").asBoolean(false)) {
          continue;
        }
        String status = HttpClients.text(meta, "status");
        if (status != null && WITHDRAWN.contains(status)) {
          String name = HttpClients.text(entry.path("server"), "name");
          if (name != null) {
            withdrawn.add(name);
            latest.remove(name);
          }
          continue;
        }
        SourceHit hit = toHit(entry.path("server"), meta, startedAt, latest.size());
        if (hit != null) {
          latest.put(hit.getExternalId(), hit.withVitality(vitalityOf(meta)));
        }
      }
      cursor = HttpClients.text(root.path("metadata"), "nextCursor");
      pages++;
    } while (cursor != null && latest.size() < maxItems && pages < 100);
    List<SourceHit> hits = new ArrayList<>(latest.values());
    boolean complete = cursor == null;
    return new CollectedBatch(
        hits,
        complete ? null : cursor,
        complete ? startedAt.toString() : null,
        false,
        List.of(),
        null,
        List.of(),
        false,
        null,
        List.copyOf(withdrawn));
  }

  private SourceHit toHit(JsonNode s, JsonNode meta, Instant now, int rank) {
    String name = HttpClients.text(s, "name");
    if (name == null) {
      return null;
    }
    String repo = HttpClients.text(s.path("repository"), "url");
    String server = serverFolder(repo, HttpClients.text(s.path("repository"), "subfolder"));
    String website = HttpClients.text(s, "websiteUrl");
    String registryEntry =
        baseUrl
            + "/v0.1/servers/"
            + URLEncoder.encode(name, StandardCharsets.UTF_8)
            + "/versions/latest";
    String link =
        server != null && CanonicalUrl.of(server) != null
            ? server
            : website != null && CanonicalUrl.of(website) != null ? website : registryEntry;
    CanonicalUrl canonical = CanonicalUrl.of(link);
    if (canonical == null) {
      return null;
    }
    String title = HttpClients.text(s, "title");
    Set<String> platforms = s.path("remotes").isEmpty() ? Set.of("local") : Set.of("remote");
    return new SourceHit(
        "mcp-registry",
        name,
        HttpClients.link(link, canonical),
        canonical,
        CanonicalUrl.of(repo),
        title == null ? name : title,
        HttpClients.text(s, "description"),
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.MCP_SERVER,
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        Set.of("mcp"),
        null,
        null,
        platforms,
        null,
        DateFact.fromSource(HttpClients.instant(meta, "publishedAt"), "mcp-registry", now),
        DateFact.fromSource(HttpClients.instant(meta, "updatedAt"), "mcp-registry", now),
        List.of(),
        now,
        rank);
  }

  private static final Set<String> WITHDRAWN = Set.of("deleted");

  private static Vitality vitalityOf(JsonNode meta) {
    return new Vitality(null, null, "deprecated".equals(HttpClients.text(meta, "status")));
  }

  private static final Pattern FOLDER = Pattern.compile("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*");

  private static final int MAX_FOLDER_LENGTH = 300;

  private static final String GITHUB = "https://github.com/";

  static String serverFolder(String repo, String subfolder) {
    CanonicalUrl repository = CanonicalUrl.of(repo);
    if (subfolder == null || repository == null || subfolder.length() > MAX_FOLDER_LENGTH) {
      return repo;
    }
    String root = repository.getValue();
    if (!root.startsWith(GITHUB) || root.substring(GITHUB.length()).split("/").length != 2) {
      return repo;
    }
    String folder = subfolder.strip();
    if (folder.startsWith("./")) {
      folder = folder.substring(2);
    }
    while (folder.endsWith("/")) {
      folder = folder.substring(0, folder.length() - 1);
    }
    boolean safe =
        FOLDER.matcher(folder).matches()
            && Arrays.stream(folder.split("/")).noneMatch(p -> p.equals(".") || p.equals(".."));
    return safe ? root + "/tree/HEAD/" + folder : repo;
  }
}
