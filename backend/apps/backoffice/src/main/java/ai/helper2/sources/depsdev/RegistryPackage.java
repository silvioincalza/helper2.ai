package ai.helper2.sources.depsdev;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record RegistryPackage(
    Ecosystem ecosystem,
    String name,
    String version,
    @Nullable Instant publishedAt,
    List<String> licenses,
    @Nullable String sourceRepo) {

  private static final String NON_STANDARD = "non-standard";

  public RegistryPackage {
    licenses = List.copyOf(licenses);
  }

  public boolean comesFrom(String repoFullName) {
    return sourceRepo != null && sourceRepo.equalsIgnoreCase("github.com/" + repoFullName);
  }

  public @Nullable String spdxLicense() {
    return licenses.size() == 1 && !NON_STANDARD.equals(licenses.getFirst())
        ? licenses.getFirst()
        : null;
  }

  public Map<String, Object> toFact() {
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("ecosystem", ecosystem.name());
    fact.put("name", name);
    fact.put("version", version);
    if (publishedAt != null) {
      fact.put("publishedAt", publishedAt.toString());
    }
    fact.put("licenses", licenses);
    return fact;
  }
}
