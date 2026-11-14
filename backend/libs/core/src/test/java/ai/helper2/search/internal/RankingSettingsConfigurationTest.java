package ai.helper2.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import ai.helper2.search.Ranking;
import ai.helper2.search.RankingWeights;
import ai.helper2.search.VitalityPolicy;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RankingSettingsConfigurationTest {

  @Test
  void theGroupShowsTheValuesTheSearchRanksWith() {
    Map<String, String> shown =
        values(RankingSettingsConfiguration.rankingSettingsGroup(new Ranking()));

    assertThat(shown)
        .containsEntry("ranking.fusionK", "60")
        .containsEntry("ranking.exactTitle", "20")
        .containsEntry("ranking.title", "20")
        .containsEntry("ranking.audience", "10")
        .containsEntry("ranking.pathType", "5")
        .containsEntry("ranking.typeHint", "5")
        .containsEntry("ranking.popularity", "8")
        .containsEntry("ranking.unmaintained", "8")
        .containsEntry("ranking.laneWindow", "10")
        .containsEntry("ranking.referenceGitHubStars", "50000")
        .containsEntry("ranking.referenceHfLikes", "5000")
        .containsEntry("ranking.referenceHfDownloads", "5000000")
        .containsEntry("ranking.referenceNpmDownloads", "1000000");
  }

  @Test
  void otherWeightsShowAsTheyAre() {
    RankingWeights weights = new RankingWeights(0.05, 0.002, 0.01, 0.005, 0.005, 0.002, 0, 0.0);
    Ranking ranking = new Ranking(40, weights, new VitalityPolicy());

    assertThat(values(RankingSettingsConfiguration.rankingSettingsGroup(ranking)))
        .containsEntry("ranking.fusionK", "40")
        .containsEntry("ranking.exactTitle", "50")
        .containsEntry("ranking.title", "2")
        .containsEntry("ranking.laneWindow", "0")
        .containsEntry("ranking.unmaintained", "0");
  }

  @Test
  void everyWeightOfTheCodeIsAWholeNumberOfThousandths() {
    RankingWeights w = new RankingWeights();

    assertThat(
            List.of(
                w.getExactTitle(),
                w.getTitle(),
                w.getAudience(),
                w.getPathType(),
                w.getTypeHint(),
                w.getPopularity(),
                w.getUnmaintained()))
        .allMatch(RankingSettingsConfiguration::isWholeThousandths);
  }

  @Test
  void noSettingHasAVariable() {
    assertThat(RankingSettingsConfiguration.rankingSettingsGroup(new Ranking()).settings())
        .allMatch(s -> s.variable() == null);
  }

  private static Map<String, String> values(SettingsGroup group) {
    return group.settings().stream().collect(Collectors.toMap(Setting::key, Setting::value));
  }
}
