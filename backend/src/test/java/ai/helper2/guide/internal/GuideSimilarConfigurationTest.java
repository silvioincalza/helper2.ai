package ai.helper2.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.catalog.Embedder;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** H2_GUIDE_SIMILAR_THRESHOLD: unset is off; set, it needs 1024-long query embeddings and a cosine in (0, 1]. */
class GuideSimilarConfigurationTest {

    private static Embedder embedder(int dimensions) {
        return new Embedder() {
            @Override
            public String model() {
                return "nvidia/nemotron-3.5-embed-1b";
            }

            @Override
            public int dimensions() {
                return dimensions;
            }

            @Override
            public List<float[]> embed(List<String> texts, Kind kind) {
                return texts.stream().map(t -> new float[dimensions]).toList();
            }
        };
    }

    private final Embedder embedder = embedder(1024);
    private final Clock clock = Clock.systemUTC();

    @Test
    void anUnsetThresholdIsOff() {
        assertThat(GuideConfiguration.similarQuestions("", embedder, null, clock)).isNull();
        assertThat(GuideConfiguration.similarQuestions(null, embedder, null, clock)).isNull();
    }

    @Test
    void withoutTheEmbedderOrWithVectorsTheIndexCannotHoldItIsOff() {
        assertThat(GuideConfiguration.similarQuestions("0.96", null, null, clock)).isNull();
        assertThat(GuideConfiguration.similarQuestions("0.96", embedder(2048), null, clock)).isNull();
    }

    @Test
    void aThresholdTurnsItOn() {
        assertThat(GuideConfiguration.similarQuestions(" 0.96 ", embedder, null, clock).getThreshold()).isEqualTo(0.96);
    }

    @Test
    void aThresholdThatIsNotACosineStopsTheStart() {
        assertThatThrownBy(() -> GuideConfiguration.similarQuestions("high", embedder, null, clock))
            .isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> GuideConfiguration.similarQuestions("1.5", embedder, null, clock))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
