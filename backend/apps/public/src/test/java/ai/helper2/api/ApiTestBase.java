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
import ai.helper2.testing.SourcesOff;
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

@SourcesOff
@SpringBootTest(
    properties = {
      "resilience4j.bulkhead.instances.source-fake-live.base-config=source",
      "helper2.rate-limit.search-per-minute=10000",
      "helper2.rate-limit.report-per-hour=10000",
      "helper2.rate-limit.default-per-minute=10000",
      "helper2.guide.fairness.budget-pace-slots=1",
      "helper2.identity.admins=test:admin-subject",
      "logging.structured.format.console=",
      "helper2.pages.template=classpath:/pages/index.html",
    })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ApiTestBase.FakeSources.class})
abstract class ApiTestBase {

  static final AtomicReference<Function<SourceQuery, List<SourceHit>>> FAKE =
      new AtomicReference<>();
  static final AtomicInteger FAKE_CALLS = new AtomicInteger();

  @Autowired MockMvc mvc;

  @Autowired StringRedisTemplate rateLimitRedis;

  @BeforeEach
  void resetFake() {
    FAKE.set(q -> List.of());
    FAKE_CALLS.set(0);
    TestcontainersConfiguration.adminRedis()
        .delete(TestcontainersConfiguration.adminRedis().keys("h2:v1:rate:*"));
  }

  @FunctionalInterface
  interface Check {
    void run() throws Exception;
  }

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

  static SourceHit fakeHit(
      String url,
      String title,
      String description,
      ResourceType type,
      String license,
      String published,
      String updated,
      int rank) {
    Instant now = Instant.now();
    return new SourceHit(
        "fake-live",
        url,
        URI.create(url),
        CanonicalUrl.of(url),
        null,
        title,
        description,
        ValueOrigin.SOURCE_PROVIDED,
        type,
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        Set.of("ai"),
        license,
        null,
        Set.of(),
        license == null ? null : Boolean.TRUE,
        DateFact.fromSource(published == null ? null : Instant.parse(published), "fake-live", now),
        DateFact.fromSource(updated == null ? null : Instant.parse(updated), "fake-live", now),
        List.of(),
        now,
        rank);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeSources {
    @Bean
    SearchSource fakeLiveSource() {
      SourceDescriptor d =
          new SourceDescriptor(
              "fake-live",
              "Fake live source",
              "Fake live",
              true,
              SourceMode.LIVE,
              URI.create("https://fake.example"),
              null,
              null,
              EnumSet.allOf(ResourceType.class),
              Duration.ofMillis(800),
              Duration.ofMinutes(10),
              true,
              5);
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
