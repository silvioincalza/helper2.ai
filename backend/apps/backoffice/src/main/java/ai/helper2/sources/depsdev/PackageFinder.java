package ai.helper2.sources.depsdev;

import ai.helper2.search.SourceFailure;
import ai.helper2.sources.depsdev.PackageSelection.PackageName;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public class PackageFinder {

  public static final int VERSION_CAP = 1500;

  public interface Registry {
    List<PackageVersion> projectVersions(String repoFullName);

    Optional<RegistryPackage> latest(Ecosystem ecosystem, String name);
  }

  private final Registry registry;
  private final int perRepo;

  public PackageFinder(Registry registry, int perRepo) {
    this.registry = registry;
    this.perRepo = perRepo;
  }

  public List<RegistryPackage> packagesOf(String repoFullName, @Nullable String language) {
    Ecosystem home = Ecosystem.ofGitHubLanguage(language).orElse(null);
    List<PackageVersion> versions = registry.projectVersions(repoFullName);
    List<RegistryPackage> found = new ArrayList<>(mapped(repoFullName, home, versions));
    if (home != null && home.takesGuessedNames() && worthGuessing(repoFullName, versions, found)) {
      guessed(repoFullName, home, found).ifPresent(found::addFirst);
    }
    return found.stream().limit(perRepo).toList();
  }

  private List<RegistryPackage> mapped(
      String repoFullName, @Nullable Ecosystem home, List<PackageVersion> versions) {
    List<PackageName> names = PackageSelection.select(repoFullName, home, versions, perRepo);
    return names.stream()
        .map(n -> latestOrEmpty(n.ecosystem(), n.name()))
        .flatMap(Optional::stream)
        .filter(p -> p.comesFrom(repoFullName))
        .toList();
  }

  private static boolean worthGuessing(
      String repoFullName, List<PackageVersion> versions, List<RegistryPackage> found) {
    List<String> guesses = NameGuesses.of(repoFullName);
    boolean capped = versions.size() >= VERSION_CAP;
    boolean named =
        found.stream().anyMatch(p -> guesses.contains(PackageSelection.normalised(p.name())));
    return capped || !named;
  }

  private Optional<RegistryPackage> guessed(
      String repoFullName, Ecosystem home, List<RegistryPackage> found) {
    for (String guess : NameGuesses.of(repoFullName)) {
      boolean known =
          found.stream()
              .anyMatch(p ->
                  p.ecosystem() == home && PackageSelection.normalised(p.name()).equals(guess));
      if (known) {
        return Optional.empty();
      }
      Optional<RegistryPackage> p =
          latestOrEmpty(home, guess).filter(candidate -> candidate.comesFrom(repoFullName));
      if (p.isPresent()) {
        return p;
      }
    }
    return Optional.empty();
  }

  private Optional<RegistryPackage> latestOrEmpty(Ecosystem ecosystem, String name) {
    try {
      return registry.latest(ecosystem, name);
    } catch (SourceFailure e) {
      return Optional.empty();
    }
  }
}
