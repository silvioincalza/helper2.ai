package ai.searchavo.sources.evidence;

import static ai.searchavo.sources.SourceFixtures.live;
import static ai.searchavo.sources.SourceFixtures.liveHttp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.ExtraText;
import ai.searchavo.scout.GapKind;
import ai.searchavo.scout.GapTarget;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class ReadmeEvidenceLiveTest {

  private ReadmeEvidence evidence() {
    return ReadmeEvidence.of(
        live("https://api.github.com", "SA_GITHUB_TOKEN"),
        live("https://huggingface.co", "SA_HF_TOKEN"),
        liveHttp(),
        liveHttp(),
        Resilience.NONE,
        Clock.systemUTC());
  }

  @Test
  void readsAGitHubReadmeAndItsLicence() {
    assumeTrue(
        !System.getenv().getOrDefault("SA_GITHUB_TOKEN", "").isBlank(), "needs SA_GITHUB_TOKEN");

    ExtraText text =
        evidence()
            .fetch(
                new GapTarget(
                    GapKind.GITHUB_README, "huggingface", "ai-deadlines", UUID.randomUUID()));

    assertThat(text).isNotNull();
    assertThat(text.getRaw()).isNotBlank();
    assertThat(text.getLicense()).isEqualTo("MIT");
  }

  @Test
  void readsAModelCardAndItsLicence() {
    ExtraText text =
        evidence()
            .fetch(
                new GapTarget(
                    GapKind.HF_MODEL_CARD, "openai", "whisper-large-v3", UUID.randomUUID()));

    assertThat(text).isNotNull();
    assertThat(text.getRaw()).contains("Whisper");
    assertThat(text.getLicense()).isEqualTo("apache-2.0");
  }
}
