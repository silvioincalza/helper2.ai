package ai.helper2.api;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full Spring context with real PostgreSQL + Redis (Testcontainers). Every real connector is
 * disabled (so tests never touch the network and must report them as SKIPPED); a controllable
 * fake LIVE source stands in.
 */
@SpringBootTest(properties = {
    "helper2.sources.github.enabled=false",
    "helper2.sources.huggingface.enabled=false",
    "helper2.sources.npm.enabled=false",
    "helper2.sources.mcp-registry.enabled=false", "helper2.sources.arxiv.enabled=false", "helper2.sources.tavily.enabled=false",
    "helper2.sources.hf-trending.enabled=false", "helper2.sources.tavily-scout.enabled=false", "helper2.sources.course-scout.enabled=false",
    "helper2.rate-limit.search-per-minute=10000",
    "helper2.rate-limit.report-per-hour=10000",
    "helper2.security.admins=test:admin-subject",
    "logging.structured.format.console=",
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ApiTestBase.FakeSources.class})
abstract class ApiTestBase {

    static final AtomicReference<Function<SourceQuery, List<SourceHit>>> FAKE = new AtomicReference<>();
    static final AtomicInteger FAKE_CALLS = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate rateLimitRedis;

    @BeforeEach
    void resetFake() {
        FAKE.set(q -> List.of());
        FAKE_CALLS.set(0);
        // Rate-limit counts are in the Redis that every test context shares: each test starts from zero.
        rateLimitRedis.delete(rateLimitRedis.keys("h2:v1:rate:*"));
    }

    @FunctionalInterface
    interface Check {
        void run() throws Exception;
    }

    /**
     * The catalog records what a search saw in the background (ResourceCatalog): checks that read it wait
     * until [check] passes, at most 5 s.
     */
    static void eventually(Check check) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (true) {
            try {
                check.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(50);
            }
        }
    }

    static SourceHit fakeHit(String url, String title, String description, ResourceType type, String license,
                             String published, String updated, int rank) {
        Instant now = Instant.now();
        return new SourceHit("fake-live", url, URI.create(url), CanonicalUrl.of(url), null, title, description,
            ValueOrigin.SOURCE_PROVIDED, type, Audience.BUILD_WITH_AI, null, Set.of(), Set.of("ai"), license, null, Set.of(),
            license == null ? null : Boolean.TRUE,
            DateFact.fromSource(published == null ? null : Instant.parse(published), "fake-live", now),
            DateFact.fromSource(updated == null ? null : Instant.parse(updated), "fake-live", now), List.of(), now, rank);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeSources {
        @Bean
        SearchSource fakeLiveSource() {
            SourceDescriptor d = new SourceDescriptor("fake-live", "Fake live source", SourceMode.LIVE,
                URI.create("https://fake.example"), null, null, EnumSet.allOf(ResourceType.class),
                Duration.ofMillis(800), Duration.ofMinutes(10), true, 5);
            return new SearchSource() {
                @Override
                public SourceDescriptor getDescriptor() {
                    return d;
                }

                @Override
                public List<SourceHit> search(SourceQuery query) {
                    FAKE_CALLS.incrementAndGet();
                    return FAKE.get().apply(query);
                }
            };
        }
    }
}
