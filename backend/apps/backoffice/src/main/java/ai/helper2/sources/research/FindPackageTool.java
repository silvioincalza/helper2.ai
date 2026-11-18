package ai.helper2.sources.research;

import ai.helper2.research.ResearchTool;
import ai.helper2.sources.depsdev.DepsDevClient;
import ai.helper2.sources.depsdev.Ecosystem;
import ai.helper2.sources.depsdev.RegistryPackage;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public final class FindPackageTool implements ResearchTool {

  private final DepsDevClient registry;

  public FindPackageTool(DepsDevClient registry) {
    this.registry = registry;
  }

  @Override
  public String name() {
    return "find_package";
  }

  @Override
  public String description() {
    return "Look up a package in its registry (npm, pypi, maven, go, cargo, nuget, rubygems): latest version, licences,"
        + " source repository.";
  }

  @Override
  public String parameters() {
    return """
    {"type":"object","properties":{"ecosystem":{"type":"string","enum":["npm","pypi","maven","go","cargo",\
    "nuget","rubygems"]},"name":{"type":"string"}},"required":["ecosystem","name"]}\
    """;
  }

  @Override
  public Output call(String arguments) {
    Args a = Args.of(arguments);
    String eco = a.text("ecosystem");
    String name = a.text("name");
    if (eco == null || name == null) {
      return Output.error("ecosystem and name are required");
    }
    Optional<Ecosystem> ecosystem =
        Arrays.stream(Ecosystem.values())
            .filter(e -> e.name().equalsIgnoreCase(eco.strip()))
            .findFirst();
    if (ecosystem.isEmpty()) {
      return Output.error("unknown ecosystem " + eco);
    }
    Optional<RegistryPackage> found = registry.latest(ecosystem.get(), name);
    if (found.isEmpty()) {
      return new Output("not found: " + name + " in " + eco, null);
    }
    RegistryPackage p = found.get();
    String url =
        "https://deps.dev/" + p.ecosystem().name().toLowerCase(Locale.ROOT) + "/" + p.name();
    return new Output(
        "package: "
            + p.name()
            + "\necosystem: "
            + p.ecosystem().name().toLowerCase(Locale.ROOT)
            + "\nversion: "
            + p.version()
            + "\nlicense: "
            + (p.licenses().isEmpty() ? "none stated" : String.join(" OR ", p.licenses()))
            + "\nsource repository: "
            + (p.sourceRepo() == null ? "none stated" : p.sourceRepo())
            + "\npublished: "
            + (p.publishedAt() == null ? "unknown" : p.publishedAt()),
        url);
  }
}
