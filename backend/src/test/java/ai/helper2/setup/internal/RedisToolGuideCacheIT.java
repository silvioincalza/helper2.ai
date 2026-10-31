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
import ai.helper2.setup.ToolGuideStatus;
import ai.helper2.setup.ToolGuideTtls;
import ai.helper2.setup.ToolGuideUnavailable;
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

/** What a tool guide answer leaves in Redis: the checked steps and document links, never a document's text. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisToolGuideCacheIT {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);
    private static final String README_ONLY = "Unrelated paragraph that only the README holds.";
    private static final ToolGuideTtls TTLS = new ToolGuideTtls(Duration.ofDays(7), Duration.ofMinutes(10));

    @Autowired
    StringRedisTemplate redis;

    @Test
    void storesTheAnswerWithoutTheDocumentText_andReadsItBack() {
        Material readme = new Material("readme", MaterialKind.README, URI.create("https://github.com/acme/x"), CLOCK.instant(),
            "Install with:\n\n```bash\nnpm install acme-x\n```\n\n" + README_ONLY + "\n");
        RedisToolGuideCache store = new RedisToolGuideCache(redis, JsonMapper.builder().build(), Resilience.NONE);
        RecordingCache recording = new RecordingCache(store);
        ToolGuides guides = new ToolGuides(ref -> List.of(readme), new Model(), recording, CLOCK, TTLS);
        String resourceId = UUID.randomUUID().toString();
        ToolRef ref = ToolRef.Companion.of("acme-x", "LIBRARY", List.of("https://github.com/acme/x"), List.of());

        ToolGuideAnswer answered = guides.answer(resourceId, ref);

        String raw = redis.opsForValue().get(RedisToolGuideCache.redisKey(recording.key));
        assertThat(raw).contains("npm install acme-x").doesNotContain(README_ONLY);
        assertThat(redis.getExpire(RedisToolGuideCache.redisKey(recording.key), TimeUnit.SECONDS))
            .isBetween(Duration.ofDays(7).minusMinutes(1).toSeconds(), Duration.ofDays(7).toSeconds());
        ToolGuideAnswer kept = store.get(recording.key);
        assertThat(kept).isEqualTo(answered);
        assertThat(kept.getSections()).containsExactly(new GuideSection(GuideSectionKind.INSTALL, List.of(
            new GuideStep("Install it.", "readme", "Install with:", new Code("bash", "npm install acme-x"), false))));
    }

    @Test
    void theNextAskIsServedFromRedisWithoutAModelCall() {
        Model model = new Model();
        RecordingCache cache = new RecordingCache(new RedisToolGuideCache(redis, JsonMapper.builder().build(), Resilience.NONE));
        ToolGuides guides = new ToolGuides(ref -> List.of(readme()), model, cache, CLOCK, TTLS);
        String resourceId = UUID.randomUUID().toString();

        ToolGuideAnswer first = guides.answer(resourceId, REF);
        ToolGuideAnswer second = guides.answer(resourceId, REF);

        assertThat(model.calls).isEqualTo(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void anUnreadableEntryCostsOneModelCall_thenTheRewrittenEntryServes() {
        Model model = new Model();
        RecordingCache cache = new RecordingCache(new RedisToolGuideCache(redis, JsonMapper.builder().build(), Resilience.NONE));
        ToolGuides guides = new ToolGuides(ref -> List.of(readme()), model, cache, CLOCK, TTLS);
        String resourceId = UUID.randomUUID().toString();
        guides.answer(resourceId, REF);
        redis.opsForValue().set(RedisToolGuideCache.redisKey(cache.key), "{\"v\":2,\"answer\":{\"status\":\"NOT_A_STATUS\"}}");

        guides.answer(resourceId, REF);
        guides.answer(resourceId, REF);
        guides.answer(resourceId, REF);

        assertThat(model.calls).isEqualTo(2);
    }

    @Test
    void anUnreadableModelAnswerIsKeptForTheShortTtl_andServesTheNextAsk() {
        Model model = new Model(new ToolGuideUnavailable(ToolGuideUnavailable.Reason.UNREADABLE));
        RecordingCache cache = new RecordingCache(new RedisToolGuideCache(redis, JsonMapper.builder().build(), Resilience.NONE));
        ToolGuides guides = new ToolGuides(ref -> List.of(readme()), model, cache, CLOCK, TTLS);
        String resourceId = UUID.randomUUID().toString();

        ToolGuideAnswer first = guides.answer(resourceId, REF);
        ToolGuideAnswer second = guides.answer(resourceId, REF);

        assertThat(redis.getExpire(RedisToolGuideCache.redisKey(cache.key), TimeUnit.SECONDS))
            .isBetween(Duration.ofMinutes(10).minusMinutes(1).toSeconds(), Duration.ofMinutes(10).toSeconds());
        assertThat(model.calls).isEqualTo(1);
        assertThat(second).isEqualTo(first);
        assertThat(second.getStatus()).isEqualTo(ToolGuideStatus.LLM_UNAVAILABLE);
        assertThat(second.getUnavailable()).isEqualTo("error");
    }

    private static Material readme() {
        return new Material("readme", MaterialKind.README, URI.create("https://github.com/acme/x"), CLOCK.instant(),
            "Install with:\n\n```bash\nnpm install acme-x\n```\n\n" + README_ONLY + "\n");
    }

    private static final ToolRef REF = ToolRef.Companion.of("acme-x", "LIBRARY", List.of("https://github.com/acme/x"), List.of());

    private static final class Model implements ToolGuideModel {
        private final RuntimeException failure;
        int calls;

        Model() {
            this(null);
        }

        Model(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public String getModel() {
            return "fake";
        }

        @Override
        public String getConfigFingerprint() {
            return "fp";
        }

        @Override
        public ProposedGuide write(ToolRef ref, List<Material> materials) {
            calls++;
            if (failure != null) {
                throw failure;
            }
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
        public void put(String key, ToolGuideAnswer answer, Duration ttl) {
            this.key = key;
            delegate.put(key, answer, ttl);
        }
    }
}
