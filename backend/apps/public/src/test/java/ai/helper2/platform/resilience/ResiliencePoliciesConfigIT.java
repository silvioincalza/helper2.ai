package ai.helper2.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.search.SourceFailure;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResiliencePoliciesConfigIT {

  static final List<String> SOURCES =
      List.of(
          "github",
          "hf-models",
          "hf-spaces",
          "npm",
          "hacker-news",
          "arxiv",
          "live-web",
          "helper2-index",
          "helper2-semantic",
          "curated");
  static final List<String> COLLECTORS =
      List.of(
          "hf-trending",
          "live-web-scout",
          "course-scout",
          "linkedin-scout",
          "github-awesome",
          "hf-provider-orgs",
          "ai-deadlines",
          "mcp-registry",
          "news-feeds",
          "pypi",
          "github-sdks",
          "deps-dev",
          "discovery");

  @Autowired Resilience resilience;

  @Autowired CircuitBreakerRegistry circuits;

  @Autowired BulkheadRegistry bulkheads;

  @Autowired RetryRegistry retries;

  @Autowired MeterRegistry meters;

  @Test
  void everySourceHasACircuitAndABulkheadButNoRetry() {
    for (String id : SOURCES) {
      String policy = "source-" + id;
      resilience.require(policy);
      assertThat(circuits.find(policy)).as(policy).isPresent();
      assertThat(bulkheads.find(policy)).as(policy).isPresent();
      assertThat(retries.find(policy)).as(policy + " never retries").isEmpty();
    }
    var github = circuits.find("source-github").orElseThrow().getCircuitBreakerConfig();
    assertThat(github.getSlidingWindowSize()).isEqualTo(20);
    assertThat(github.getFailureRateThreshold()).isEqualTo(50f);
    assertThat(github.getIgnoreExceptionPredicate().test(new SourceFailure.RateLimited(null)))
        .isTrue();
    assertThat(
            bulkheads.find("source-github").orElseThrow().getBulkheadConfig().getMaxWaitDuration())
        .isEqualTo(Duration.ZERO);
  }

  @Test
  void everyCollectorRetriesOnlyTransientFailures() {
    for (String id : COLLECTORS) {
      String policy = "collector-" + id;
      assertThat(circuits.find(policy)).as(policy).isPresent();
      var retry = retries.find(policy).orElseThrow().getRetryConfig();
      assertThat(retry.getMaxAttempts()).isEqualTo(3);
      assertThat(
              retry.getExceptionPredicate().test(new SourceFailure.Unavailable("HTTP 503", true)))
          .isTrue();
      assertThat(retry.getExceptionPredicate().test(new SourceFailure.Unavailable("HTTP 404")))
          .isFalse();
    }
  }

  @Test
  void redisHasACircuitOnly() {
    assertThat(circuits.find("redis")).isPresent();
    assertThat(bulkheads.find("redis")).isEmpty();
    assertThat(retries.find("redis")).isEmpty();
  }

  @Test
  void theSharedCachesHaveACircuitOnSlowCallsAndASmallBulkhead() {
    var circuit = circuits.find("db-cache").orElseThrow().getCircuitBreakerConfig();
    assertThat(circuit.getSlowCallDurationThreshold()).isEqualTo(Duration.ofMillis(250));
    var bulkhead = bulkheads.find("db-cache").orElseThrow().getBulkheadConfig();
    assertThat(bulkhead.getMaxConcurrentCalls()).isEqualTo(2);
    assertThat(bulkhead.getMaxWaitDuration()).isEqualTo(Duration.ZERO);
    assertThat(retries.find("db-cache")).isEmpty();
  }

  @Test
  void modelCallsHaveACircuitOnlyAndGuideQuestionsABulkhead() {
    for (String policy : List.of("llm-chat", "llm-chat-guide", "llm-embed", "llm-embed-index")) {
      assertThat(circuits.find(policy)).as(policy).isPresent();
      assertThat(bulkheads.find(policy))
          .as(policy + " is limited per question, not per call")
          .isEmpty();
      assertThat(retries.find(policy))
          .as(policy + " never retries: every attempt is charged")
          .isEmpty();
    }
    var chat = circuits.find("llm-chat").orElseThrow().getCircuitBreakerConfig();
    assertThat(
            chat.getIgnoreExceptionPredicate()
                .test(new IllegalStateException("not the provider's")))
        .isTrue();
    var guideCalls = circuits.find("llm-chat-guide").orElseThrow().getCircuitBreakerConfig();
    assertThat(guideCalls.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(35));
    assertThat(
            guideCalls
                .getIgnoreExceptionPredicate()
                .test(new IllegalStateException("not the provider's")))
        .isTrue();
    assertThat(chat.getSlowCallDurationThreshold())
        .as("translation and the setup guide do not open on slow guide calls")
        .isGreaterThan(Duration.ofSeconds(40));
    var guide = bulkheads.find("guide").orElseThrow().getBulkheadConfig();
    assertThat(guide.getMaxConcurrentCalls()).isEqualTo(8);
    assertThat(guide.getMaxWaitDuration()).isEqualTo(Duration.ZERO);
  }

  @Test
  void circuitStateAndRefusedCallsAreMetered() {
    assertThat(
            meters
                .find("resilience4j.circuitbreaker.state")
                .tag("name", "source-github")
                .tag("state", "open")
                .gauge())
        .isNotNull();
    assertThat(
            meters
                .find("resilience4j.circuitbreaker.not.permitted.calls")
                .tag("name", "redis")
                .counter())
        .isNotNull();
    assertThat(
            meters
                .find("resilience4j.bulkhead.available.concurrent.calls")
                .tag("name", "source-live-web")
                .gauge())
        .isNotNull();
  }
}
