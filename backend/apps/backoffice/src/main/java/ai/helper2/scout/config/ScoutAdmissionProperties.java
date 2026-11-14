package ai.helper2.scout.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.scout.admission")
public record ScoutAdmissionProperties(
    @Valid @NotNull GitHubTopicLeaders githubTopicLeaders, @Valid @NotNull HfTrending hfTrending) {

  public record GitHubTopicLeaders(
      @PositiveOrZero int indexMinStars, @NotNull Duration indexPushedWithin) {}

  public record HfTrending(@PositiveOrZero int indexMinLikes) {}
}
