package ai.helper2.sources.depsdev;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public final class PackageSelection {

  public record PackageName(Ecosystem ecosystem, String name) {}

  private static final Set<String> GENERIC_TOKENS =
      Set.of("com", "org", "io", "net", "dev", "github", "www");

  private static final Set<String> NOT_A_LIBRARY =
      Set.of("example", "examples", "sample", "samples");

  private PackageSelection() {}

  public static List<PackageName> select(
      String repoFullName,
      @Nullable Ecosystem repoEcosystem,
      List<PackageVersion> versions,
      int limit) {
    Origin origin = Origin.of(repoFullName);
    return grouped(versions).values().stream()
        .filter(p -> p.ecosystem() != Ecosystem.GO || repoEcosystem == Ecosystem.GO)
        .filter(p -> !p.name().contains(">"))
        .filter(p -> isLibrary(p.name()))
        .filter(p -> p.verified() || origin.owns(p.ecosystem(), p.name()))
        .sorted(mainFirst(repoEcosystem))
        .limit(limit)
        .map(p -> new PackageName(p.ecosystem(), p.name()))
        .toList();
  }

  private record Mapped(Ecosystem ecosystem, String name, int versions, boolean verified) {
    Mapped plus(PackageVersion v) {
      return new Mapped(ecosystem, name, versions + 1, verified || v.verified());
    }
  }

  private static Map<PackageName, Mapped> grouped(List<PackageVersion> versions) {
    Map<PackageName, Mapped> byName = new LinkedHashMap<>();
    for (PackageVersion v : versions) {
      byName.merge(
          new PackageName(v.ecosystem(), v.name()),
          new Mapped(v.ecosystem(), v.name(), 1, v.verified()),
          (a, b) -> a.plus(v));
    }
    return byName;
  }

  private static Comparator<Mapped> mainFirst(@Nullable Ecosystem repoEcosystem) {
    return Comparator.<Mapped>comparingInt(p -> p.ecosystem() == repoEcosystem ? 0 : 1)
        .thenComparing(Comparator.comparingInt(Mapped::versions).reversed())
        .thenComparingInt(p -> p.name().length())
        .thenComparing(Mapped::name);
  }

  private static boolean isLibrary(String name) {
    String last = lastSegment(name);
    if (last.equals("bom")
        || last.equals("parent")
        || last.endsWith("-bom")
        || last.endsWith("-parent")) {
      return false;
    }
    return tokens(name).stream().noneMatch(NOT_A_LIBRARY::contains);
  }

  private record Origin(Set<String> repoWords, List<String> guesses) {

    static Origin of(String repoFullName) {
      return new Origin(PackageSelection.tokens(repoFullName), NameGuesses.of(repoFullName));
    }

    boolean owns(Ecosystem ecosystem, String name) {
      String namespace = namespaceOf(ecosystem, name);
      if (namespace != null) {
        return PackageSelection.tokens(namespace).stream().anyMatch(this::nearToken);
      }
      String plain = normalised(name);
      return guesses.stream()
          .anyMatch(g ->
              plain.equals(g)
                  || plain.startsWith(g + "-")
                  || (plain.endsWith("-" + g)
                      && nearToken(plain.substring(0, plain.length() - g.length() - 1))));
    }

    private boolean nearToken(String namespaceToken) {
      return namespaceToken.length() >= MIN_NEAR
          && repoWords.stream()
              .filter(t -> t.length() >= MIN_NEAR)
              .anyMatch(t -> t.startsWith(namespaceToken) || namespaceToken.startsWith(t));
    }
  }

  private static final int MIN_NEAR = 3;

  private static @Nullable String namespaceOf(Ecosystem ecosystem, String name) {
    return switch (ecosystem) {
      case NPM ->
          name.startsWith("@") && name.contains("/") ? name.substring(1, name.indexOf('/')) : null;
      case MAVEN -> name.contains(":") ? name.substring(0, name.indexOf(':')) : null;
      case NUGET -> name.contains(".") ? name.substring(0, name.indexOf('.')) : null;
      default -> null;
    };
  }

  public static String normalised(String name) {
    return name.toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static String lastSegment(String name) {
    int cut = Math.max(name.lastIndexOf(':'), name.lastIndexOf('/'));
    return name.substring(cut + 1).toLowerCase(Locale.ROOT);
  }

  static String repoNameOf(String repoFullName) {
    int slash = repoFullName.indexOf('/');
    return slash < 0 ? repoFullName : repoFullName.substring(slash + 1);
  }

  static Set<String> tokens(String text) {
    return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
        .filter(t -> t.length() > 1 && !GENERIC_TOKENS.contains(t))
        .collect(Collectors.toSet());
  }
}
