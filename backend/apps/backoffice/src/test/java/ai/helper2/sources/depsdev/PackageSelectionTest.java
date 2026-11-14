package ai.helper2.sources.depsdev;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.sources.depsdev.PackageSelection.PackageName;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackageSelectionTest {

  static final String UNVERIFIED = PackageVersion.UNVERIFIED;

  static List<PackageVersion> versions(Ecosystem e, String name, String provenance, int count) {
    List<PackageVersion> list = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      list.add(new PackageVersion(e, name, provenance));
    }
    return list;
  }

  static List<PackageVersion> all(List<List<PackageVersion>> parts) {
    return parts.stream().flatMap(List::stream).toList();
  }

  @Test
  void theGoModuleOfARepoInAnotherLanguageIsNotAPackage() {
    List<PackageName> picked =
        PackageSelection.select(
            "openai/openai-java",
            Ecosystem.MAVEN,
            all(
                List.of(
                    versions(Ecosystem.GO, "github.com/openai/openai-java", "GO_ORIGIN", 9),
                    versions(Ecosystem.MAVEN, "com.openai:openai-java", UNVERIFIED, 3))),
            3);

    assertThat(picked).containsExactly(new PackageName(Ecosystem.MAVEN, "com.openai:openai-java"));
  }

  @Test
  void aGoRepoKeepsItsModule() {
    List<PackageName> picked =
        PackageSelection.select(
            "anthropics/anthropic-sdk-go",
            Ecosystem.GO,
            versions(Ecosystem.GO, "github.com/anthropics/anthropic-sdk-go", "GO_ORIGIN", 2),
            3);

    assertThat(picked)
        .extracting(PackageName::name)
        .containsExactly("github.com/anthropics/anthropic-sdk-go");
  }

  @Test
  void bundledCopiesBomsParentsExamplesAndSamplesAreDropped() {
    List<PackageName> picked =
        PackageSelection.select(
            "googleapis/js-genai",
            Ecosystem.NPM,
            all(
                List.of(
                    versions(Ecosystem.NPM, "@adrouter/cli>0.81.0>@google/genai", UNVERIFIED, 5),
                    versions(Ecosystem.MAVEN, "com.google.genai:genai-bom", UNVERIFIED, 5),
                    versions(Ecosystem.MAVEN, "com.google.genai:genai-parent", UNVERIFIED, 5),
                    versions(Ecosystem.NPM, "genai-examples", UNVERIFIED, 5),
                    versions(Ecosystem.NPM, "genai-samples", UNVERIFIED, 5),
                    versions(Ecosystem.NPM, "@google/genai", UNVERIFIED, 1))),
            5);

    assertThat(picked).extracting(PackageName::name).containsExactly("@google/genai");
  }

  @Test
  void anUnverifiedPackageThatSharesNoWordWithTheRepoIsALookAlike() {
    List<PackageName> picked =
        PackageSelection.select(
            "anthropics/anthropic-sdk-python",
            Ecosystem.PYPI,
            all(
                List.of(
                    versions(Ecosystem.PYPI, "mseep-tools", UNVERIFIED, 4),
                    versions(Ecosystem.PYPI, "anthropic", UNVERIFIED, 2))),
            3);

    assertThat(picked).extracting(PackageName::name).containsExactly("anthropic");
  }

  @Test
  void aPackageWithAPublishAttestationStaysWhateverItsName() {
    List<PackageName> picked =
        PackageSelection.select(
            "vercel/ai",
            Ecosystem.NPM,
            versions(Ecosystem.NPM, "next-gen-kit", "SLSA_ATTESTATION", 1),
            3);

    assertThat(picked).extracting(PackageName::name).containsExactly("next-gen-kit");
  }

  @Test
  void theRepoLanguageComesFirstThenTheMostReleasedThenTheShortestName() {
    List<PackageName> picked =
        PackageSelection.select(
            "openai/openai-java",
            Ecosystem.MAVEN,
            all(
                List.of(
                    versions(Ecosystem.NPM, "openai-java-wrapper", UNVERIFIED, 50),
                    versions(Ecosystem.MAVEN, "com.openai:openai-java-core", UNVERIFIED, 7),
                    versions(Ecosystem.MAVEN, "com.openai:openai-java", UNVERIFIED, 7),
                    versions(Ecosystem.MAVEN, "com.openai:openai-java-vertex", UNVERIFIED, 2))),
            3);

    assertThat(picked)
        .extracting(PackageName::name)
        .containsExactly(
            "com.openai:openai-java",
            "com.openai:openai-java-core",
            "com.openai:openai-java-vertex");
  }

  @Test
  void anUnverifiedScopedNpmPackageMustBeInTheOwnersScope() {
    List<PackageName> picked =
        PackageSelection.select(
            "openai/openai-node",
            Ecosystem.NPM,
            all(
                List.of(
                    versions(Ecosystem.NPM, "@depup/openai", UNVERIFIED, 9),
                    versions(Ecosystem.NPM, "@openai/agents-core", UNVERIFIED, 2),
                    versions(Ecosystem.NPM, "openai", UNVERIFIED, 1))),
            3);

    assertThat(picked)
        .extracting(PackageName::name)
        .containsExactly("@openai/agents-core", "openai");
  }

  @Test
  void anUnverifiedMavenGroupMustBeNearTheOwnerOrTheRepoName() {
    List<PackageName> picked =
        PackageSelection.select(
            "oracle/tribuo",
            Ecosystem.MAVEN,
            all(
                List.of(
                    versions(Ecosystem.MAVEN, "org.tribuo:tribuo-core", UNVERIFIED, 3),
                    versions(Ecosystem.MAVEN, "io.mirror:tribuo-core", UNVERIFIED, 5))),
            3);

    assertThat(picked).extracting(PackageName::name).containsExactly("org.tribuo:tribuo-core");
  }

  @Test
  void anUnverifiedPlainNameMustBeTheRepoNameOrStartWithIt() {
    List<PackageName> picked =
        PackageSelection.select(
            "huggingface/candle",
            Ecosystem.CARGO,
            all(
                List.of(
                    versions(Ecosystem.CARGO, "candle_core", UNVERIFIED, 3),
                    versions(Ecosystem.CARGO, "my_candle", UNVERIFIED, 5))),
            3);

    assertThat(picked).extracting(PackageName::name).containsExactly("candle_core");
  }

  @Test
  void anUnverifiedPlainNameMayBeTheOwnersPrefixAndARepoWord() {
    List<PackageName> picked =
        PackageSelection.select(
            "googleapis/python-genai",
            Ecosystem.PYPI,
            all(
                List.of(
                    versions(Ecosystem.PYPI, "google-genai", UNVERIFIED, 3),
                    versions(Ecosystem.PYPI, "mseep-genai", UNVERIFIED, 5))),
            3);

    assertThat(picked).extracting(PackageName::name).containsExactly("google-genai");
  }

  @Test
  void aMavenArtifactNamedBomOrParentIsDropped() {
    List<PackageName> picked =
        PackageSelection.select(
            "deepjavalibrary/djl",
            Ecosystem.MAVEN,
            all(
                List.of(
                    versions(Ecosystem.MAVEN, "ai.djl:bom", UNVERIFIED, 9),
                    versions(Ecosystem.MAVEN, "ai.djl:parent", UNVERIFIED, 9),
                    versions(Ecosystem.MAVEN, "ai.djl:api", UNVERIFIED, 3))),
            3);

    assertThat(picked).extracting(PackageName::name).containsExactly("ai.djl:api");
  }
}
