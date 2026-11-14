package ai.helper2.search.internal;

import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.Ranking;
import ai.helper2.search.RankingWeights;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RankingSettingsConfiguration {

  static final String WEIGHT_UNIT = "thousandths";

  private static final int THOUSAND = 1000;

  @Bean
  SettingsGroup rankingSettings(Ranking ranking) {
    return rankingSettingsGroup(ranking);
  }

  static SettingsGroup rankingSettingsGroup(Ranking ranking) {
    RankingWeights w = ranking.getWeights();
    return new SettingsGroup(
        "ranking",
        "Ranking",
        SettingsGroup.RANKING,
        List.of(
            Setting.fixedCount("ranking.fusionK", "Fusion constant k", ranking.getK(), "ranks"),
            weight("ranking.exactTitle", "Title equals the query", w.getExactTitle()),
            weight("ranking.title", "Title holds every word", w.getTitle()),
            weight("ranking.audience", "Audience of the chosen path", w.getAudience()),
            weight("ranking.pathType", "Type of the chosen path", w.getPathType()),
            weight("ranking.typeHint", "Type named in the query", w.getTypeHint()),
            weight("ranking.popularity", "Popularity, at most", w.getPopularity()),
            weight("ranking.unmaintained", "Unmaintained, taken off", w.getUnmaintained()),
            Setting.fixedCount(
                "ranking.laneWindow",
                "Places searched for the other lane",
                w.getLaneWindow(),
                "results"),
            reference(
                "ranking.referenceGitHubStars",
                "Full popularity, GitHub",
                PopularityKind.GITHUB_STARS,
                "stars"),
            reference(
                "ranking.referenceHfLikes",
                "Full popularity, Hugging Face likes",
                PopularityKind.HF_LIKES,
                "likes"),
            reference(
                "ranking.referenceHfDownloads",
                "Full popularity, Hugging Face downloads",
                PopularityKind.HF_DOWNLOADS,
                "downloads"),
            reference(
                "ranking.referenceNpmDownloads",
                "Full popularity, npm",
                PopularityKind.NPM_DOWNLOADS_WEEKLY,
                "downloads a week")));
  }

  static boolean isWholeThousandths(double weight) {
    return Math.abs(weight * THOUSAND - Math.round(weight * THOUSAND)) < 1e-9;
  }

  private static Setting weight(String key, String label, double weight) {
    return Setting.fixedCount(key, label, Math.round(weight * THOUSAND), WEIGHT_UNIT);
  }

  private static Setting reference(String key, String label, PopularityKind kind, String unit) {
    return Setting.fixedCount(key, label, Math.round(Ranking.POPULARITY_REFERENCE.get(kind)), unit);
  }
}
