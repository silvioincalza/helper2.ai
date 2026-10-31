package ai.helper2.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.catalog.Embedder;
import ai.helper2.platform.resilience.Resilience;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** H2_GUIDE_SIMILAR_THRESHOLD: unset is off; set, it needs the embedder and Redis, and a cosine in (0, 1]. */
class GuideSimilarConfigurationTest {

    private final Embedder embedder = new Embedder() {
        @Override
        public String model() {
            return "nvidia/nemotron-3.5-embed-1b";
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public List<float[]> embed(List<String> texts, Kind kind) {
            return texts.stream().map(t -> new float[] {1f, 0f}).toList();
        }
    };
    private final StringRedisTemplate redis = new StringRedisTemplate();
    private final Clock clock = Clock.systemUTC();

    @Test
    void anUnsetThresholdIsOff() {
        assertThat(GuideConfiguration.similarQuestions("", embedder, redis, Resilience.NONE, clock)).isNull();
        assertThat(GuideConfiguration.similarQuestions(null, embedder, redis, Resilience.NONE, clock)).isNull();
    }

    @Test
    void withoutTheEmbedderOrRedisItIsOff() {
        assertThat(GuideConfiguration.similarQuestions("0.95", null, redis, Resilience.NONE, clock)).isNull();
        assertThat(GuideConfiguration.similarQuestions("0.95", embedder, null, Resilience.NONE, clock)).isNull();
    }

    @Test
    void aThresholdTurnsItOn() {
        assertThat(GuideConfiguration.similarQuestions(" 0.95 ", embedder, redis, Resilience.NONE, clock).getThreshold()).isEqualTo(0.95);
    }

    @Test
    void aThresholdThatIsNotACosineStopsTheStart() {
        assertThatThrownBy(() -> GuideConfiguration.similarQuestions("high", embedder, redis, Resilience.NONE, clock))
            .isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> GuideConfiguration.similarQuestions("1.5", embedder, redis, Resilience.NONE, clock))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
