package ai.helper2.sources.mcp;

import static ai.helper2.sources.SourceFixtures.live;
import static ai.helper2.sources.SourceFixtures.liveHttp;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.support.RunInterval;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Opt-in: the official MCP Registry (no key). ./gradlew liveTest. One page of servers. */
@Tag("live")
class McpRegistryCollectorLiveTest {

    @Test
    void onePageOfServersAndACursorForTheNext() {
        McpRegistryCollector collector = new McpRegistryCollector(live("https://registry.modelcontextprotocol.io", null), liveHttp(),
            Clock.systemUTC(), Resilience.NONE, new RunInterval(Duration.ZERO));

        CollectedBatch batch = collector.collect(null, null, 20);

        assertThat(batch.getHits()).isNotEmpty().allSatisfy(h -> assertThat(h.getType()).isEqualTo(ResourceType.MCP_SERVER));
        assertThat(batch.getNextCursor()).isNotNull();
    }
}
