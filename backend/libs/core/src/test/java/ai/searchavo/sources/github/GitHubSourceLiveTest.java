package ai.searchavo.sources.github;

import static ai.searchavo.sources.SourceFixtures.live;
import static ai.searchavo.sources.SourceFixtures.liveHttp;
import static ai.searchavo.sources.SourceFixtures.query;
import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.search.SourceHit;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class GitHubSourceLiveTest {

  @Test
  void searchFindsRepositoriesWithStarsAndDates() {
    GitHubSource source =
        new GitHubSource(
            live("https://api.github.com", "SA_GITHUB_TOKEN"), liveHttp(), Clock.systemUTC());

    List<SourceHit> hits = source.search(query("mcp server postgres"));

    assertThat(hits)
        .isNotEmpty()
        .allSatisfy(h -> {
          assertThat(h.getUrl().getHost()).isEqualTo("github.com");
          assertThat(h.getPopularity()).isNotEmpty();
          assertThat(h.getUpdated().getValue()).isNotNull();
        });
  }
}
