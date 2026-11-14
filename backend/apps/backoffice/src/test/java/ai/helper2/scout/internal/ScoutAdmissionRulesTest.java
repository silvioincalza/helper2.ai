package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.scout.admission.AdmissionRules;
import ai.helper2.scout.admission.IndexFloor;
import ai.helper2.scout.config.ScoutAdmissionProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ScoutAdmissionRulesTest {

  final AdmissionRules rules =
      new ScoutConfiguration()
          .admissionRules(
              new ScoutAdmissionProperties(
                  new ScoutAdmissionProperties.GitHubTopicLeaders(1000, Duration.ofDays(90)),
                  new ScoutAdmissionProperties.HfTrending(20)));

  @Test
  void eachCollectorWithAFloorHasItsProfile() {
    assertThat(rules.of("github-topic-leaders"))
        .isEqualTo(new IndexFloor(1000, Duration.ofDays(90)));
    assertThat(rules.of("hf-trending")).isEqualTo(new IndexFloor(0, null, 20));
    assertThat(rules.of("pypi")).isEqualTo(new IndexFloor(0, null, 0, true));
  }

  @Test
  void aCollectorWithoutAProfileHasNoFloor() {
    assertThat(rules.of("github-awesome")).isEqualTo(IndexFloor.NONE);
  }
}
