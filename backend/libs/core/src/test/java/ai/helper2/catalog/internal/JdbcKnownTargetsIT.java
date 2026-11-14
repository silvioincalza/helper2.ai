package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargets;
import ai.helper2.testing.AsBackOffice;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcKnownTargetsIT {

  @Autowired KnownTargets known;
  @Autowired JdbcClient jdbc;

  private KnownTarget named(String target) {
    return known.named(List.of(target)).get(KnownTarget.key(target));
  }

  @Test
  void theSchemaStartsWithTheVendorsAndEveryNameThatMeansThem() {
    assertThat(
            jdbc.sql("SELECT count(*) FROM known_target WHERE kind = 'VENDOR'")
                .query(Integer.class)
                .single())
        .isEqualTo(31);
    assertThat(named("OpenAI")).isEqualTo(new KnownTarget("OpenAI", KnownTarget.Kind.VENDOR));
    assertThat(named("Amazon Web Services").name()).isEqualTo("AWS");
    assertThat(named("HuggingFace").name()).isEqualTo("Hugging Face");
    assertThat(named("DeepMind").name()).isEqualTo("Google DeepMind");
    assertThat(named("Z.ai").name()).isEqualTo("Zhipu AI");
  }

  @Test
  void aNameIsFoundWhateverItsCaseSpacesOrPossessive() {
    assertThat(named("  openai ").name()).isEqualTo("OpenAI");
    assertThat(named("Open  AI").name()).isEqualTo("OpenAI");
    assertThat(named("OpenAI's").name()).isEqualTo("OpenAI");
    assertThat(named("Anthropic\u2019s").name()).isEqualTo("Anthropic");
  }

  @Test
  void aProductOfAVendorOrAnUnknownNameIsNoKnownTarget() {
    Map<String, KnownTarget> found =
        known.named(List.of("OpenAI Codex", "Claude Code", "Azure OpenAI", "Google Drive", ""));

    assertThat(found).isEmpty();
  }

  @Test
  void severalNamesAreReadInOneCallEachUnderItsKey() {
    Map<String, KnownTarget> found = known.named(List.of("OpenAI", "Open AI", "Cohere", "Nobody"));

    assertThat(found).containsOnlyKeys("openai", "open ai", "cohere");
    assertThat(found.get("open ai").name()).isEqualTo("OpenAI");
  }
}
