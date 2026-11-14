package ai.helper2.sources.depsdev;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public enum Ecosystem {
  MAVEN("maven"),
  PYPI("pypi pip"),
  NPM("npm"),
  GO("go golang"),
  CARGO("cargo crate crates"),
  NUGET("nuget dotnet"),
  RUBYGEMS("rubygems gem");

  private final String searchWords;

  Ecosystem(String searchWords) {
    this.searchWords = searchWords;
  }

  public String searchWords() {
    return searchWords;
  }

  private static final Map<String, Ecosystem> BY_GITHUB_LANGUAGE =
      Map.of(
          "java", MAVEN,
          "kotlin", MAVEN,
          "scala", MAVEN,
          "python", PYPI,
          "typescript", NPM,
          "javascript", NPM,
          "go", GO,
          "rust", CARGO,
          "c#", NUGET,
          "ruby", RUBYGEMS);

  public static Optional<Ecosystem> ofGitHubLanguage(String language) {
    return language == null
        ? Optional.empty()
        : Optional.ofNullable(BY_GITHUB_LANGUAGE.get(language.toLowerCase(Locale.ROOT)));
  }

  public static Optional<Ecosystem> ofSystem(String system) {
    try {
      return system == null
          ? Optional.empty()
          : Optional.of(valueOf(system.toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  public boolean takesGuessedNames() {
    return this != MAVEN && this != GO;
  }
}
