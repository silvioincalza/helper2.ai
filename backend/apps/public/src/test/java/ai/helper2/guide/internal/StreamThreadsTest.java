package ai.helper2.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.guide.CallTimeLimit;
import ai.helper2.guide.config.ModelStream;
import ai.helper2.guide.internal.FakeProvider.Reply;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.llm.StreamThreads;
import ai.helper2.platform.resilience.Resilience;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StreamThreadsTest {

  static final int QUESTIONS = 8;
  static final String SDK_STREAM_HANDLER = "openai-stream-handler-thread";
  static final List<String> NOT_OURS =
      List.of(
          SDK_STREAM_HANDLER,
          "boundedElastic",
          "OkHttp",
          "Okio",
          "HTTP-Dispatcher",
          "ForkJoinPool",
          "VirtualThread-",
          "Read-Poller",
          "Write-Poller");

  final FakeProvider provider =
      new FakeProvider().otherwise(Reply.stallsAfter("{\"searchQuery\":\"MCP"));

  @AfterEach
  void stop() {
    provider.close();
  }

  private SpringAiGuideModel streaming() {
    return new SpringAiGuideModel(
        provider.chatModel(StreamThreads.virtualDispatcher()),
        "test",
        "fast",
        "reasoning",
        true,
        8192,
        SpringAiGuideModel.Thinking.DEFAULT,
        new DailyBudget(100, Clock.systemUTC(), "guide"),
        ModelCallMetrics.none(),
        Resilience.NONE,
        CallTimeLimit.NONE,
        Clock.systemUTC(),
        ModelStream.ON);
  }

  private static Set<Thread> platformThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> !thread.isVirtual())
        .collect(Collectors.toSet());
  }

  private void untilEveryStreamIsOpen() throws InterruptedException {
    long giveUp = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (provider.bodies.size() < QUESTIONS && System.nanoTime() < giveUp) {
      Thread.sleep(Duration.ofMillis(20));
    }
    Thread.sleep(Duration.ofMillis(300));
  }

  @Test
  void questionsStreamingAtOnceNeedNoPlatformThreadBeyondTheOneTheSdkHoldsPerStream()
      throws InterruptedException {
    SpringAiGuideModel model = streaming();
    Set<Thread> before = platformThreads();

    List<Thread> questions =
        IntStream.range(0, QUESTIONS)
            .mapToObj(i -> Thread.ofVirtual().start(() -> swallow(model)))
            .toList();
    untilEveryStreamIsOpen();
    List<String> added =
        platformThreads().stream()
            .filter(thread -> !before.contains(thread))
            .map(Thread::getName)
            .toList();
    questions.forEach(Thread::interrupt);
    for (Thread question : questions) {
      question.join(Duration.ofSeconds(5));
    }

    assertThat(provider.bodies).hasSize(QUESTIONS);
    assertThat(added).allMatch(name -> NOT_OURS.stream().anyMatch(name::startsWith));
    assertThat(added).filteredOn(name -> name.startsWith(SDK_STREAM_HANDLER)).hasSize(QUESTIONS);
    assertThat(added).noneMatch(name -> name.startsWith("OkHttp Dispatcher"));
    assertThat(provider.closedByTheClientAfter(QUESTIONS)).hasSize(QUESTIONS);
  }

  private static void swallow(SpringAiGuideModel model) {
    try {
      model.plan("postgres MCP", null);
    } catch (RuntimeException stopped) {
      Thread.currentThread().interrupt();
    }
  }
}
