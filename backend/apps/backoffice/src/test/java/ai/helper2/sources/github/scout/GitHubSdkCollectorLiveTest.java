package ai.helper2.sources.github.scout;

import static ai.helper2.sources.SourceFixtures.live;
import static ai.helper2.sources.SourceFixtures.liveHttp;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.depsdev.DepsDevClient;
import ai.helper2.sources.depsdev.Ecosystem;
import ai.helper2.sources.depsdev.PackageFinder;
import ai.helper2.sources.depsdev.RegistryPackage;
import ai.helper2.sources.github.scout.SdkSearchPlan.LanguageFloor;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class GitHubSdkCollectorLiveTest {

  final DepsDevClient depsDev =
      new DepsDevClient(
          liveHttp().build(),
          "https://api.deps.dev",
          Clock.systemUTC(),
          Resilience.NONE,
          GitHubSdkCollector.PACKAGES_POLICY);

  @Test
  void depsDevMapsAnOfficialSdkRepoToItsPackage() {
    List<RegistryPackage> found =
        new PackageFinder(depsDev, 3).packagesOf("anthropics/anthropic-sdk-java", "Kotlin");

    assertThat(found)
        .anySatisfy(p -> {
          assertThat(p.ecosystem()).isEqualTo(Ecosystem.MAVEN);
          assertThat(p.name()).isEqualTo("com.anthropic:anthropic-java");
          assertThat(p.publishedAt()).isNotNull();
        });
  }

  @Test
  void theNameGuessFindsThePackageTheVersionCapHides() {
    List<RegistryPackage> found =
        new PackageFinder(depsDev, 3).packagesOf("vercel/ai", "TypeScript");

    assertThat(found).extracting(RegistryPackage::name).first().isEqualTo("ai");
  }

  @Test
  void aMissingPythonPackageIsEmptyAndTheGuessStillFindsOpenai() {
    List<RegistryPackage> found =
        new PackageFinder(depsDev, 3).packagesOf("openai/openai-python", "Python");

    assertThat(found).extracting(RegistryPackage::name).contains("openai");
  }

  @Test
  void oneOrgQueryProposesItsAiReposWithPackages() {
    SourceSettings settings = live("https://api.github.com", "H2_GITHUB_TOKEN");
    GitHubSdkCollector collector =
        new GitHubSdkCollector(
            settings,
            liveHttp(),
            Clock.systemUTC(),
            Resilience.NONE,
            new RunInterval(Duration.ZERO),
            new GitHubSdkCollector.Limits(
                SdkSearchPlan.of(List.of(), List.<LanguageFloor>of(), List.of("anthropics"), 300),
                Set.of("anthropics"),
                10,
                1,
                1,
                5,
                Duration.ZERO,
                25),
            new GitHubSdkCollector.Ports(
                new PackageFinder(depsDev, 3),
                new GitHubSdkCollector.ReviewRoom() {
                  @Override
                  public boolean known(ai.helper2.shared.CanonicalUrl url) {
                    return false;
                  }

                  @Override
                  public int roomToday() {
                    return 25;
                  }
                },
                GitHubSdkCollector.Pause.NONE),
            CollectorDescriptors.gitHubSdks(settings, List.of(), List.of("anthropics")));

    CollectedBatch batch = collector.collect(null, null, 100);

    assertThat(batch.getCandidates())
        .isNotEmpty()
        .anySatisfy(c ->
            assertThat((List<?>) ((Map<?, ?>) c.getFacts().get(SdkFacts.KEY)).get("packages"))
                .isNotEmpty());
  }
}
