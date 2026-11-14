package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.sources.depsdev.Ecosystem;
import ai.helper2.sources.depsdev.RegistryPackage;
import java.util.List;
import org.junit.jupiter.api.Test;

class SdkSearchTermsTest {

  static RegistryPackage pkg(Ecosystem e, String name) {
    return new RegistryPackage(e, name, "1.0.0", null, List.of(), null);
  }

  @Test
  void anOfficialSdkGivesItsLanguagesPackageWordsRegistryAndTheProvidersOtherName() {
    String terms =
        SdkSearchTerms.of(
            "anthropics/anthropic-sdk-java",
            List.of("Kotlin", "Java"),
            true,
            List.of(pkg(Ecosystem.MAVEN, "com.anthropic:anthropic-java")));

    assertThat(terms).isEqualTo("kotlin java com anthropic maven library package sdk claude");
  }

  @Test
  void aCommunityCrateIsALibraryButNotAnSdk() {
    String terms =
        SdkSearchTerms.of(
            "acme/forge", List.of("Rust"), false, List.of(pkg(Ecosystem.CARGO, "forge-core")));

    assertThat(terms).isEqualTo("rust forge core cargo crate crates library package");
  }

  @Test
  void withNoPackageOnlyTheLanguagesAndAProviderNamedByTheRepoRemain() {
    assertThat(SdkSearchTerms.of("acme/go-openai", List.of("Go"), false, List.of()))
        .isEqualTo("go golang chatgpt openai");
  }

  @Test
  void csharpAlsoReadsAsDotnet() {
    assertThat(SdkSearchTerms.of("acme/kit", List.of("C#"), false, List.of()))
        .isEqualTo("csharp dotnet");
  }
}
