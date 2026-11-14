package ai.helper2.sources.depsdev;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PackageFinderTest {

  static class FakeRegistry implements PackageFinder.Registry {
    final Map<String, List<PackageVersion>> projects = new HashMap<>();
    final Map<String, RegistryPackage> packages = new HashMap<>();
    final List<String> asked = new ArrayList<>();
    final java.util.Set<String> failing = new java.util.HashSet<>();

    @Override
    public List<PackageVersion> projectVersions(String repoFullName) {
      return projects.getOrDefault(repoFullName, List.of());
    }

    @Override
    public Optional<RegistryPackage> latest(Ecosystem ecosystem, String name) {
      asked.add(ecosystem + ":" + name);
      if (failing.contains(name)) {
        throw new ai.helper2.search.SourceFailure.Unavailable("HTTP 503", true);
      }
      return Optional.ofNullable(packages.get(ecosystem + ":" + name));
    }

    void publish(Ecosystem e, String name, String repo) {
      packages.put(
          e + ":" + name,
          new RegistryPackage(
              e, name, "1.0.0", Instant.parse("2026-11-01T00:00:00Z"), List.of("MIT"), repo));
    }
  }

  final FakeRegistry registry = new FakeRegistry();
  final PackageFinder finder = new PackageFinder(registry, 3);

  @Test
  void aMappedPackageCountsOnlyWhenItsLatestVersionPointsBackToTheRepo() {
    registry.projects.put(
        "openai/openai-node",
        List.of(
            new PackageVersion(Ecosystem.NPM, "openai", "SLSA_ATTESTATION"),
            new PackageVersion(Ecosystem.NPM, "openai-fork", PackageVersion.UNVERIFIED)));
    registry.publish(Ecosystem.NPM, "openai", "github.com/openai/openai-node");
    registry.publish(Ecosystem.NPM, "openai-fork", "github.com/someone/openai-fork");

    List<RegistryPackage> found = finder.packagesOf("openai/openai-node", "TypeScript");

    assertThat(found).extracting(RegistryPackage::name).containsExactly("openai");
  }

  @Test
  void whenTheMappingMissesTheRepoLanguageTheRepoNameIsTriedInItsRegistry() {
    registry.publish(Ecosystem.PYPI, "openai", "github.com/openai/openai-python");

    List<RegistryPackage> found = finder.packagesOf("openai/openai-python", "Python");

    assertThat(found).extracting(RegistryPackage::name).containsExactly("openai");
    assertThat(registry.asked).containsExactly("PYPI:openai-python", "PYPI:openai");
  }

  @Test
  void aGuessedNameOfAnotherRepoIsNotTaken() {
    registry.publish(Ecosystem.PYPI, "genai", "github.com/someone/genai");

    assertThat(finder.packagesOf("acme/python-genai", "Python")).isEmpty();
  }

  @Test
  void mavenAndGoNamesAreNeverGuessed() {
    assertThat(finder.packagesOf("googleapis/java-genai", "Java")).isEmpty();
    assertThat(registry.asked).isEmpty();
  }

  @Test
  void oneFailingLookupDropsOnlyThatPackage() {
    registry.projects.put(
        "anthropics/anthropic-sdk-typescript",
        List.of(
            new PackageVersion(Ecosystem.NPM, "@anthropic-ai/sdk", "SLSA_ATTESTATION"),
            new PackageVersion(Ecosystem.NPM, "@anthropic-ai/bedrock-sdk", "SLSA_ATTESTATION")));
    registry.publish(
        Ecosystem.NPM, "@anthropic-ai/sdk", "github.com/anthropics/anthropic-sdk-typescript");
    registry.failing.add("@anthropic-ai/bedrock-sdk");

    assertThat(finder.packagesOf("anthropics/anthropic-sdk-typescript", "TypeScript"))
        .extracting(RegistryPackage::name)
        .containsExactly("@anthropic-ai/sdk");
  }

  @Test
  void whenTheAnswerHitsTheVersionCapTheRepoNameIsTriedAndComesFirst() {
    List<PackageVersion> capped = new ArrayList<>();
    for (int i = 0; i < PackageFinder.VERSION_CAP; i++) {
      capped.add(new PackageVersion(Ecosystem.NPM, "@ai-sdk/openai", "SLSA_ATTESTATION"));
    }
    registry.projects.put("vercel/ai", capped);
    registry.publish(Ecosystem.NPM, "@ai-sdk/openai", "github.com/vercel/ai");
    registry.publish(Ecosystem.NPM, "ai", "github.com/vercel/ai");

    assertThat(finder.packagesOf("vercel/ai", "TypeScript"))
        .extracting(RegistryPackage::name)
        .containsExactly("ai", "@ai-sdk/openai");
  }
}
