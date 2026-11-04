package ai.helper2.sources.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.sources.support.SourceSettings;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A collector's deadline (ADR 0006 §2): twice its timeout unless H2_SOURCE_<ID>_DEADLINE says otherwise. */
class SourcesPropertiesTest {

    @Test
    void theDeadlineIsTwiceTheTimeoutUnlessSet() {
        var properties = new SourcesConfiguration.SourcesProperties(Map.of(
            "plain", new SourceSettings(true, "https://x.example", "", Duration.ofSeconds(10), null),
            "set", new SourceSettings(true, "https://x.example", "", Duration.ofSeconds(10), null, Duration.ofSeconds(45)),
            "bare", new SourceSettings(true, "https://x.example", "", null, null)));

        assertThat(properties.get("plain").deadline()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.get("set").deadline()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.get("bare").deadline()).as("default timeout 2 s").isEqualTo(Duration.ofSeconds(4));
    }
}
