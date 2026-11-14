package ai.helper2.sources.github.scout;

import ai.helper2.scout.usecase.PackageFacts;
import ai.helper2.sources.depsdev.RegistryPackage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SdkFacts {

  public static final String KEY = PackageFacts.KEY;

  private SdkFacts() {}

  public static Map<String, Object> of(
      String repoFullName,
      List<String> languages,
      boolean official,
      List<RegistryPackage> packages) {
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("languages", languages);
    fact.put("official", official);
    fact.put("packages", packages.stream().map(RegistryPackage::toFact).toList());
    if (!packages.isEmpty()) {
      RegistryPackage main = packages.getFirst();
      if (main.spdxLicense() != null) {
        fact.put("license", main.spdxLicense());
      }
      if (main.publishedAt() != null) {
        fact.put("latestRelease", main.publishedAt().toString());
      }
    }
    fact.put(PackageFacts.TERMS, SdkSearchTerms.of(repoFullName, languages, official, packages));
    return fact;
  }

  public static String languageTag(String gitHubLanguage) {
    return gitHubLanguage
        .toLowerCase(Locale.ROOT)
        .replace("c#", "csharp")
        .replace("c++", "cpp")
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-|-$)", "");
  }
}
