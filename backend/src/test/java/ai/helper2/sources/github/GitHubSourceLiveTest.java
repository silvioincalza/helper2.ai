package ai.helper2.sources.github;

import static ai.helper2.sources.SourceFixtures.live;
import static ai.helper2.sources.SourceFixtures.liveHttp;
import static ai.helper2.sources.SourceFixtures.query;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.search.SourceHit;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in: the real GitHub search API (anonymous works, H2_GITHUB_TOKEN raises the limit). ./gradlew liveTest
 * One call. Fails when GitHub changes the response shape the mapper reads.
 */
@Tag("live")
class GitHubSourceLiveTest {

    @Test
    void searchFindsRepositoriesWithStarsAndDates() {
        GitHubSource source = new GitHubSource(live("https://api.github.com", "H2_GITHUB_TOKEN"), liveHttp(), Clock.systemUTC());

        List<SourceHit> hits = source.search(query("mcp server postgres"));

        assertThat(hits).isNotEmpty().allSatisfy(h -> {
            assertThat(h.getUrl().getHost()).isEqualTo("github.com");
            assertThat(h.getPopularity()).isNotEmpty();
            assertThat(h.getUpdated().getValue()).isNotNull();
        });
    }
}
