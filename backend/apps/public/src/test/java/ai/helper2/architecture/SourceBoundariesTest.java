package ai.helper2.architecture;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.BackendSources;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SourceBoundariesTest {

  private static final Map<String, String> LIBRARY_OF_PACKAGE =
      Map.ofEntries(
          entry("shared", "kernel"),
          entry("platform", "platform"),
          entry("testing", "platform"),
          entry("identity", "identity"),
          entry("search", "search"),
          entry("search.web", "web-contract"),
          entry("api", "web-contract"),
          entry("catalog", "catalog"),
          entry("examples", "examples"),
          entry("sources", "sources"));

  private static final String LIBRARY_OF_THE_ROOT_PACKAGE = "platform";

  private static final Map<String, Set<String>> LIBRARY_USES =
      Map.of(
          "kernel", Set.of(),
          "platform", Set.of("kernel"),
          "identity", Set.of("platform", "kernel"),
          "search", Set.of("platform", "kernel"),
          "web-contract", Set.of("search", "platform", "kernel"),
          "catalog", Set.of("search", "platform", "kernel"),
          "examples", Set.of("catalog", "search", "platform", "kernel"),
          "sources", Set.of("search", "platform", "kernel"));

  private static final Path BASE = Path.of("ai", "helper2");

  private static final String PACKAGE_INFO = "package-info.java";

  private static final Pattern REFERENCE = Pattern.compile("\\bai\\.helper2\\.(\\w+(?:\\.\\w+)*)");

  private static final Pattern APPLICATION_MODULE =
      Pattern.compile("@(?:org\\.springframework\\.modulith\\.)?ApplicationModule\\b");

  private static final Pattern OPEN_MODULE = Pattern.compile("\\bType\\.OPEN\\b");

  private static final Pattern NAMED_INTERFACE =
      Pattern.compile("@(?:org\\.springframework\\.modulith\\.)?NamedInterface\\b");

  private static final List<SourceFile> MAIN = sourceFiles(BackendSources.mainSourceRoots());

  private static final List<SourceFile> LIBRARIES =
      sourceFiles(BackendSources.librarySourceRoots());

  private static final Set<String> PACKAGES =
      Stream.concat(MAIN.stream(), LIBRARIES.stream())
          .flatMap(file -> withParents(file.packageName()))
          .collect(Collectors.toSet());

  private static final Set<String> MODULES =
      MAIN.stream()
          .filter(file -> file.declares(APPLICATION_MODULE))
          .map(SourceFile::packageName)
          .collect(Collectors.toSet());

  private static final Set<String> OPEN_MODULES =
      MAIN.stream()
          .filter(file -> file.declares(APPLICATION_MODULE) && file.declares(OPEN_MODULE))
          .map(SourceFile::packageName)
          .collect(Collectors.toSet());

  private static final Set<String> API_PACKAGES =
      MAIN.stream()
          .filter(file -> file.isPackageInfo() && file.declares(NAMED_INTERFACE))
          .map(SourceFile::packageName)
          .collect(Collectors.toSet());

  private static final Set<String> API_TYPES =
      MAIN.stream()
          .filter(file -> !file.isPackageInfo() && file.declares(NAMED_INTERFACE))
          .map(SourceFile::typeName)
          .collect(Collectors.toSet());

  @Test
  void theModulesAreReadFromTheSources() {
    assertThat(MODULES)
        .contains("search", "catalog", "examples", "identity", "sources", "guide", "scout");
    assertThat(OPEN_MODULES).containsExactlyInAnyOrder("shared", "platform", "api");
    assertThat(API_PACKAGES).contains("search.usecase", "catalog.usecase", "catalog.enrichment");
    assertThat(API_TYPES).contains("search.web.CardMapper");
  }

  @Test
  void everyLibraryPackageBelongsToAContext() {
    assertThat(
            LIBRARIES.stream()
                .filter(file -> libraryOf(file.packageName()).isEmpty())
                .map(SourceFile::path))
        .as("library files in a package that no context owns")
        .isEmpty();
  }

  @Test
  void aLibraryContextUsesOnlyTheContextsBelowIt() {
    assertThat(
            LIBRARIES.stream()
                .filter(file -> !file.statesArchitectureRules())
                .flatMap(file -> file.uses(used -> !libraryMayUse(file, used))))
        .as("source references across a library boundary")
        .isEmpty();
  }

  @Test
  void aModuleUsesOnlyTheApiOfAnotherModule() {
    assertThat(
            MAIN.stream()
                .filter(file -> MODULES.contains(file.module()))
                .flatMap(file -> file.uses(used -> !moduleMayUse(file, used))))
        .as("source references to a package that is not the API of its module")
        .isEmpty();
  }

  @Test
  void nothingOutsideSourcesNamesIt() {
    assertThat(
            MAIN.stream()
                .filter(file -> !file.module().equals("sources"))
                .flatMap(file -> file.uses(used -> used.module().equals("sources"))))
        .as("source references to the sources module from outside it")
        .isEmpty();
  }

  private static boolean libraryMayUse(SourceFile file, Reference used) {
    Optional<String> user = libraryOf(file.packageName());
    Optional<String> owner = libraryOf(used.packageName());
    if (user.isEmpty() || owner.isEmpty()) {
      return false;
    }
    return owner.equals(user) || LIBRARY_USES.get(user.get()).contains(owner.get());
  }

  private static boolean moduleMayUse(SourceFile file, Reference used) {
    String module = used.module();
    return module.equals(file.module())
        || !MODULES.contains(module)
        || OPEN_MODULES.contains(module)
        || used.packageName().equals(module)
        || API_PACKAGES.contains(used.packageName())
        || API_TYPES.contains(used.name());
  }

  private static Optional<String> libraryOf(String packageName) {
    if (packageName.isEmpty()) {
      return Optional.of(LIBRARY_OF_THE_ROOT_PACKAGE);
    }
    return withParents(packageName)
        .filter(LIBRARY_OF_PACKAGE::containsKey)
        .map(LIBRARY_OF_PACKAGE::get)
        .findFirst();
  }

  private static Stream<String> withParents(String packageName) {
    return Stream.iterate(
        packageName,
        name -> !name.isEmpty(),
        name -> name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "");
  }

  private static Optional<Reference> reference(String dotted) {
    List<String> parts = List.of(dotted.split("\\."));
    for (int length = parts.size(); length > 0; length--) {
      String packageName = String.join(".", parts.subList(0, length));
      if (PACKAGES.contains(packageName)) {
        return Optional.of(
            new Reference(packageName, length < parts.size() ? parts.get(length) : ""));
      }
    }
    return Optional.empty();
  }

  private static List<SourceFile> sourceFiles(List<Path> roots) {
    return roots.stream()
        .filter(root -> Files.isDirectory(root.resolve(BASE)))
        .flatMap(SourceBoundariesTest::sourceFilesUnder)
        .toList();
  }

  private static Stream<SourceFile> sourceFilesUnder(Path root) {
    Path base = root.resolve(BASE);
    try (Stream<Path> files = Files.walk(base)) {
      return files
          .filter(Files::isRegularFile)
          .filter(file -> file.toString().endsWith(".java") || file.toString().endsWith(".kt"))
          .map(file -> new SourceFile(file, packageName(base.relativize(file)), read(file)))
          .toList()
          .stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String packageName(Path relative) {
    Path directory = relative.getParent();
    return directory == null
        ? ""
        : directory.toString().replace(directory.getFileSystem().getSeparator(), ".");
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String moduleOf(String packageName) {
    return packageName.contains(".")
        ? packageName.substring(0, packageName.indexOf('.'))
        : packageName;
  }

  private record Reference(String packageName, String member) {

    String module() {
      return moduleOf(packageName);
    }

    String name() {
      return member.isEmpty() ? packageName : packageName + "." + member;
    }
  }

  private record SourceFile(Path path, String packageName, String text) {

    String module() {
      return moduleOf(packageName);
    }

    boolean isPackageInfo() {
      return path.getFileName().toString().equals(PACKAGE_INFO);
    }

    boolean declares(Pattern annotation) {
      return annotation.matcher(text).find();
    }

    boolean statesArchitectureRules() {
      return withParents(packageName).anyMatch(name -> name.endsWith("architecture"));
    }

    String typeName() {
      String file = path.getFileName().toString();
      return packageName + "." + file.substring(0, file.lastIndexOf('.'));
    }

    Stream<String> uses(Predicate<Reference> forbidden) {
      return REFERENCE
          .matcher(text)
          .results()
          .map(MatchResult::group)
          .map(match -> match.substring("ai.helper2.".length()))
          .map(SourceBoundariesTest::reference)
          .flatMap(Optional::stream)
          .filter(used -> !used.packageName().equals(packageName))
          .filter(forbidden)
          .map(used -> path + " uses ai.helper2." + used.name())
          .collect(Collectors.toCollection(TreeSet::new))
          .stream();
    }
  }
}
