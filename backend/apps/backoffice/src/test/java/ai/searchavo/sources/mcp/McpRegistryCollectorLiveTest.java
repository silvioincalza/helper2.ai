package ai.searchavo.sources.mcp;

import static ai.searchavo.sources.SourceFixtures.live;
import static ai.searchavo.sources.SourceFixtures.liveHttp;
import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.CollectedBatch;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.sources.support.RunInterval;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class McpRegistryCollectorLiveTest {

  @Test
  void onePageOfServersAndACursorForTheNext() {
    McpRegistryCollector collector =
        new McpRegistryCollector(
            live("https://registry.modelcontextprotocol.io", null),
            liveHttp(),
            Clock.systemUTC(),
            Resilience.NONE,
            new RunInterval(Duration.ZERO),
            0);

    CollectedBatch batch = collector.collect(null, null, 20);

    assertThat(batch.getHits())
        .isNotEmpty()
        .allSatisfy(h -> assertThat(h.getType()).isEqualTo(ResourceType.MCP_SERVER));
    assertThat(batch.getNextCursor()).isNotNull();
  }
}
