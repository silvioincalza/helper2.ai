package ai.helper2.sources.depsdev;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.sources.support.HttpClients;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

public class DepsDevClient implements PackageFinder.Registry {

  private static final String SOURCE_REPO = "SOURCE_REPO";

  private static final int NOT_FOUND = 404;

  private static final JsonNode EMPTY = JsonNodeFactory.instance.objectNode();

  private final RestClient http;
  private final String baseUrl;
  private final Clock clock;
  private final Resilience resilience;
  private final String policy;

  public DepsDevClient(
      RestClient http, String baseUrl, Clock clock, Resilience resilience, String policy) {
    this.http = http;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.clock = clock;
    this.resilience = resilience;
    this.policy = policy;
  }

  @Override
  public List<PackageVersion> projectVersions(String repoFullName) {
    String project = "github.com/" + repoFullName.toLowerCase(Locale.ROOT);
    JsonNode body = get("/v3/projects/" + encode(project) + ":packageversions").orElse(EMPTY);
    List<PackageVersion> versions = new ArrayList<>();
    for (JsonNode v : body.path("versions")) {
      if (!SOURCE_REPO.equals(v.path("relationType").asString(""))) {
        continue;
      }
      JsonNode key = v.path("versionKey");
      String name = HttpClients.text(key, "name");
      Optional<Ecosystem> ecosystem = Ecosystem.ofSystem(HttpClients.text(key, "system"));
      if (name != null && ecosystem.isPresent()) {
        versions.add(
            new PackageVersion(
                ecosystem.get(),
                name,
                v.path("relationProvenance").asString(PackageVersion.UNVERIFIED)));
      }
    }
    return versions;
  }

  @Override
  public Optional<RegistryPackage> latest(Ecosystem ecosystem, String name) {
    String packagePath = "/v3/systems/" + ecosystem.name() + "/packages/" + encode(name);
    String version = get(packagePath).map(DepsDevClient::defaultVersion).orElse(null);
    if (version == null) {
      return Optional.empty();
    }
    Optional<JsonNode> found = get(packagePath + "/versions/" + encode(version));
    if (found.isEmpty() || found.get().path("versionKey").isMissingNode()) {
      return Optional.empty();
    }
    JsonNode v = found.get();
    return Optional.of(
        new RegistryPackage(
            ecosystem,
            name,
            version,
            HttpClients.instant(v, "publishedAt"),
            licenses(v),
            sourceRepo(v)));
  }

  private static @Nullable String defaultVersion(JsonNode pkg) {
    String last = null;
    for (JsonNode v : pkg.path("versions")) {
      String version = HttpClients.text(v.path("versionKey"), "version");
      if (v.path("isDefault").asBoolean(false)) {
        return version;
      }
      last = version;
    }
    return last;
  }

  private static List<String> licenses(JsonNode v) {
    List<String> licenses = new ArrayList<>();
    v.path("licenses").forEach(l -> licenses.add(l.asString()));
    return licenses;
  }

  private static @Nullable String sourceRepo(JsonNode v) {
    for (JsonNode p : v.path("relatedProjects")) {
      if (SOURCE_REPO.equals(p.path("relationType").asString(""))) {
        return HttpClients.text(p.path("projectKey"), "id");
      }
    }
    return null;
  }

  private Optional<JsonNode> get(String path) {
    URI uri = URI.create(baseUrl + path);
    return resilience.call(
        policy,
        () -> {
          ResponseEntity<String> response =
              HttpClients.fetch(
                  () ->
                      http.get()
                          .uri(uri)
                          .retrieve()
                          .onStatus(s -> s.value() == NOT_FOUND, (request, reply) -> {})
                          .toEntity(String.class),
                  clock);
          if (response.getStatusCode().value() == NOT_FOUND) {
            return Optional.empty();
          }
          return Optional.of(HttpClients.getJson(response::getBody, clock));
        });
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
