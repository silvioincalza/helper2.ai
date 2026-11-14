package ai.helper2.sources.depsdev;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class NameGuesses {

  private static final List<String> PREFIXES = List.of("python-", "py-", "node-", "js-", "rust-");

  private static final List<String> SUFFIXES =
      List.of(
          "-python",
          "-py",
          "-node",
          "-js",
          "-ts",
          "-typescript",
          "-rs",
          "-rust",
          "-ruby",
          "-rb",
          "-dotnet",
          "-csharp",
          "-net",
          "-sdk",
          "-client");

  private static final int MAX_GUESSES = 3;

  private NameGuesses() {}

  public static List<String> of(String repoFullName) {
    String name = PackageSelection.normalised(PackageSelection.repoNameOf(repoFullName));
    Set<String> guesses = new LinkedHashSet<>();
    guesses.add(name);
    String current = name;
    String next = strip(current);
    while (!next.equals(current)) {
      guesses.add(next);
      current = next;
      next = strip(current);
    }
    return guesses.stream().filter(g -> !g.isEmpty()).limit(MAX_GUESSES).toList();
  }

  private static String strip(String name) {
    for (String prefix : PREFIXES) {
      if (name.startsWith(prefix) && name.length() > prefix.length()) {
        return name.substring(prefix.length());
      }
    }
    for (String suffix : SUFFIXES) {
      if (name.endsWith(suffix) && name.length() > suffix.length()) {
        return name.substring(0, name.length() - suffix.length());
      }
    }
    return name;
  }
}
