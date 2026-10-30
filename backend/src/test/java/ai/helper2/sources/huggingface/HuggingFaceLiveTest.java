package ai.helper2.sources.huggingface;

import static ai.helper2.sources.SourceFixtures.live;
import static ai.helper2.sources.SourceFixtures.liveHttp;
import static ai.helper2.sources.SourceFixtures.query;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.support.RunInterval;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in: the real Hugging Face Hub API, one test per source and collector (H2_HF_TOKEN optional). ./gradlew liveTest
 * Four Hub calls. Fails when the Hub changes the listing fields the mappers read.
 */
@Tag("live")
class HuggingFaceLiveTest {

    static final String HUB = "https://huggingface.co";

    @Test
    void modelsSearchFindsModelsWithLikes() {
        HuggingFaceModelsSource source = new HuggingFaceModelsSource(live(HUB, "H2_HF_TOKEN"), liveHttp(), Clock.systemUTC());

        List<SourceHit> hits = source.search(query("qwen"));

        assertThat(hits).isNotEmpty().allSatisfy(h -> {
            assertThat(h.getUrl().toString()).startsWith(HUB + "/");
            assertThat(h.getType()).isEqualTo(ResourceType.MODEL);
        });
    }

    @Test
    void spacesSearchFindsApps() {
        HuggingFaceSpacesSource source = new HuggingFaceSpacesSource(live(HUB, "H2_HF_TOKEN"), liveHttp(), Clock.systemUTC());

        List<SourceHit> hits = source.search(query("translate"));

        assertThat(hits).isNotEmpty().allSatisfy(h -> assertThat(h.getUrl().toString()).startsWith(HUB + "/spaces/"));
    }

    @Test
    void trendingCollectorReadsTheCurrentList() {
        HuggingFaceTrendingCollector collector = new HuggingFaceTrendingCollector(live(HUB, "H2_HF_TOKEN"), liveHttp(), Clock.systemUTC(),
            Resilience.NONE, new RunInterval(Duration.ZERO), 20);

        CollectedBatch batch = collector.collect(null, null, 20);

        assertThat(batch.getHits()).isNotEmpty().allSatisfy(h -> assertThat(h.getType()).isEqualTo(ResourceType.MODEL));
        assertThat(batch.getCheckpoint()).isNotNull();
    }

    @Test
    void providerOrgsCollectorReadsTheNewestModelsOfAnOrg() {
        HuggingFaceProviderOrgsCollector collector = new HuggingFaceProviderOrgsCollector(live(HUB, "H2_HF_TOKEN"), liveHttp(),
            Clock.systemUTC(), Resilience.NONE, new RunInterval(Duration.ZERO), List.of("Qwen"), 5);

        CollectedBatch batch = collector.collect(null, null, 20);

        assertThat(batch.getHits()).isNotEmpty().allSatisfy(h -> assertThat(h.getUrl().toString()).startsWith(HUB + "/Qwen/"));
    }
}
