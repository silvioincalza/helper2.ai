package ai.helper2.setup.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.setup.Code;
import ai.helper2.setup.GuideSection;
import ai.helper2.setup.GuideSectionKind;
import ai.helper2.setup.GuideStep;
import ai.helper2.setup.Material;
import ai.helper2.setup.MaterialKind;
import ai.helper2.setup.ToolGuideAnswer;
import ai.helper2.setup.ToolGuideCache;
import ai.helper2.setup.ToolGuideModel;
import ai.helper2.setup.ProposedGuide;
import ai.helper2.setup.ProposedSection;
import ai.helper2.setup.ProposedStep;
import ai.helper2.setup.ToolGuides;
import ai.helper2.setup.ToolRef;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** What a tool guide answer leaves in Redis: the checked steps and document links, never the question or a document's text. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisToolGuideCacheIT {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);
    private static final String QUESTION = "Does it work with my secret project zebra-42?";
    private static final String README_ONLY = "Unrelated paragraph that only the README holds.";

    @Autowired
    StringRedisTemplate redis;

    @Test
    void storesTheAnswerWithoutTheQuestionOrTheDocumentText_andReadsItBack() {
        Material readme = new Material("readme", MaterialKind.README, URI.create("https://github.com/acme/x"), CLOCK.instant(),
            "Install with:\n\n```bash\nnpm install acme-x\n```\n\n" + README_ONLY + "\n");
        RedisToolGuideCache store = new RedisToolGuideCache(redis, JsonMapper.builder().build(), Resilience.NONE, Duration.ofDays(7));
        RecordingCache recording = new RecordingCache(store);
        ToolGuides guides = new ToolGuides(ref -> List.of(readme), new Model(), recording, CLOCK);
        String resourceId = UUID.randomUUID().toString();
        ToolRef ref = ToolRef.Companion.of("acme-x", "LIBRARY", List.of("https://github.com/acme/x"), List.of());

        ToolGuideAnswer asked = guides.answer(resourceId, ref, QUESTION);

        String raw = redis.opsForValue().get(RedisToolGuideCache.redisKey(recording.key));
        assertThat(raw).contains("npm install acme-x").doesNotContain("zebra-42").doesNotContain(README_ONLY);
        assertThat(redis.getExpire(RedisToolGuideCache.redisKey(recording.key), TimeUnit.SECONDS))
            .isBetween(Duration.ofDays(7).minusMinutes(1).toSeconds(), Duration.ofDays(7).toSeconds());
        ToolGuideAnswer kept = store.get(recording.key);
        assertThat(kept).isEqualTo(asked.copy(asked.getStatus(), null, asked.getSections(), asked.getMaterials(), asked.getModel(),
            asked.getRejected(), asked.getGeneratedAt(), asked.getRejectedBy(), asked.getUnavailable()));
        assertThat(kept.getSections()).containsExactly(new GuideSection(GuideSectionKind.INSTALL, List.of(
            new GuideStep("Install it.", "readme", "Install with:", new Code("bash", "npm install acme-x"), false))));
    }

    private static final class Model implements ToolGuideModel {
        @Override
        public String getModel() {
            return "fake";
        }

        @Override
        public String getConfigFingerprint() {
            return "fp";
        }

        @Override
        public ProposedGuide write(ToolRef ref, List<Material> materials, String question) {
            return new ProposedGuide(List.of(new ProposedSection("INSTALL",
                List.of(new ProposedStep("Install it.", "readme", "Install with:", "npm install acme-x", null)))));
        }
    }

    private static final class RecordingCache implements ToolGuideCache {
        private final ToolGuideCache delegate;
        String key;

        RecordingCache(ToolGuideCache delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolGuideAnswer get(String key) {
            return delegate.get(key);
        }

        @Override
        public void put(String key, ToolGuideAnswer answer) {
            this.key = key;
            delegate.put(key, answer);
        }
    }
}
