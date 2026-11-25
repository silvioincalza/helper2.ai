package ai.searchavo.sources.npm;

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
class NpmSourceLiveTest {

  @Test
  void searchFindsPackagesWithTheirRegistryPage() {
    NpmSource source =
        new NpmSource(live("https://registry.npmjs.org", null), liveHttp(), Clock.systemUTC());

    List<SourceHit> hits = source.search(query("mcp server"));

    assertThat(hits)
        .isNotEmpty()
        .allSatisfy(h -> assertThat(h.getUrl().getHost()).endsWith("npmjs.com"));
  }
}
