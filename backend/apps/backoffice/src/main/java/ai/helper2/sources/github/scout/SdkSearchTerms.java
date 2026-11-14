package ai.helper2.sources.github.scout;

import ai.helper2.search.Compatibility;
import ai.helper2.sources.depsdev.RegistryPackage;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class SdkSearchTerms {

  public static final int MAX_LENGTH = 2000;

  private static final Map<String, String> LANGUAGE_WORDS =
      Map.of("csharp", "csharp dotnet", "go", "go golang");

  private static final List<Set<String>> PROVIDER_NAMES =
      Compatibility.INSTANCE.getTOOLS().stream()
          .map(tool ->
              tool.getTags().stream()
                  .filter(tag -> tag.matches("[a-z0-9]+"))
                  .collect(Collectors.toUnmodifiableSet()))
          .filter(names -> !names.isEmpty())
          .toList();

  private SdkSearchTerms() {}

  public static String of(
      String repoFullName,
      List<String> languages,
      boolean official,
      List<RegistryPackage> packages) {
    Set<String> terms = new LinkedHashSet<>();
    languages.forEach(l -> terms.addAll(words(languageWords(SdkFacts.languageTag(l)))));
    for (RegistryPackage p : packages) {
      terms.addAll(words(p.name()));
      terms.addAll(words(p.ecosystem().searchWords()));
    }
    if (!packages.isEmpty()) {
      terms.addAll(List.of("library", "package"));
    }
    if (official) {
      terms.add("sdk");
    }
    terms.addAll(providerNames(terms, words(repoFullName)));
    return cut(String.join(" ", terms));
  }

  private static String languageWords(String tag) {
    return LANGUAGE_WORDS.getOrDefault(tag, tag);
  }

  private static Set<String> providerNames(Set<String> terms, Set<String> repoWords) {
    Set<String> names = new LinkedHashSet<>();
    for (Set<String> provider : PROVIDER_NAMES) {
      boolean named = provider.stream().anyMatch(n -> terms.contains(n) || repoWords.contains(n));
      if (named) {
        provider.stream().sorted().forEach(names::add);
      }
    }
    return names;
  }

  private static Set<String> words(String text) {
    return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
        .filter(w -> w.length() > 1)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static String cut(String terms) {
    if (terms.length() <= MAX_LENGTH) {
      return terms;
    }
    int space = terms.lastIndexOf(' ', MAX_LENGTH);
    return terms.substring(0, space < 0 ? MAX_LENGTH : space);
  }
}
