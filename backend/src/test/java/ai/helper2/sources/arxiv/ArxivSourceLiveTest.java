package ai.helper2.sources.arxiv;

import static ai.helper2.sources.SourceFixtures.live;
import static ai.helper2.sources.SourceFixtures.liveHttp;
import static ai.helper2.sources.SourceFixtures.query;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.search.SourceHit;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Opt-in: the real arXiv API (no key; one call, within its one request per 3 s). ./gradlew liveTest */
@Tag("live")
class ArxivSourceLiveTest {

    @Test
    void searchFindsPapersWithPublicationDates() {
        ArxivSource source = new ArxivSource(live("https://export.arxiv.org", null), liveHttp(), Clock.systemUTC(), (host, interval) -> true);

        List<SourceHit> hits = source.search(query("retrieval augmented generation"));

        assertThat(hits).isNotEmpty().allSatisfy(h -> {
            assertThat(h.getUrl().getHost()).isEqualTo("arxiv.org");
            assertThat(h.getPublished().getValue()).isNotNull();
        });
    }
}
