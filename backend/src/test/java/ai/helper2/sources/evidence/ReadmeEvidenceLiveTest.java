package ai.helper2.sources.evidence;

import static ai.helper2.sources.SourceFixtures.live;
import static ai.helper2.sources.SourceFixtures.liveHttp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.ExtraText;
import ai.helper2.scout.GapKind;
import ai.helper2.scout.GapTarget;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in: the real GitHub README and Hugging Face model-card endpoints of the gap round (ADR 0043). ./gradlew liveTest
 * The model card is read with H2_HF_TOKEN when it is set, as on prod: a redirect of the raw path to another host would
 * be refused then (ObservedHttp), and every model card would fail.
 */
@Tag("live")
class ReadmeEvidenceLiveTest {

    private ReadmeEvidence evidence() {
        return ReadmeEvidence.of(live("https://api.github.com", "H2_GITHUB_TOKEN"), live("https://huggingface.co", "H2_HF_TOKEN"),
            liveHttp(), liveHttp(), Resilience.NONE, Clock.systemUTC());
    }

    @Test
    void readsAGitHubReadmeAndItsLicence() {
        assumeTrue(!System.getenv().getOrDefault("H2_GITHUB_TOKEN", "").isBlank(), "needs H2_GITHUB_TOKEN");

        ExtraText text = evidence().fetch(new GapTarget(GapKind.GITHUB_README, "huggingface", "ai-deadlines", UUID.randomUUID()));

        assertThat(text).isNotNull();
        assertThat(text.getRaw()).isNotBlank();
        assertThat(text.getLicense()).isEqualTo("MIT");
    }

    @Test
    void readsAModelCardAndItsLicence() {
        ExtraText text = evidence().fetch(new GapTarget(GapKind.HF_MODEL_CARD, "openai", "whisper-large-v3", UUID.randomUUID()));

        assertThat(text).isNotNull();
        assertThat(text.getRaw()).contains("Whisper");
        assertThat(text.getLicense()).isEqualTo("apache-2.0");
    }
}
