package ai.searchavo.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.guide.GuideJudge;
import ai.searchavo.guide.config.GuideProperties;
import ai.searchavo.platform.llm.CallCounter;
import ai.searchavo.platform.resilience.Resilience;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JudgeBulkheadTest {

  final List<Runnable> started = new ArrayList<>();
  final AtomicInteger dropped = new AtomicInteger();
  final AtomicInteger ran = new AtomicInteger();
  final JudgeBulkhead bulkhead = new JudgeBulkhead(2, started::add, dropped::incrementAndGet);

  @Test
  void judgingBeyondTheSlotsIsDroppedAndAFinishedOneFreesItsSlot() {
    bulkhead.execute(ran::incrementAndGet);
    bulkhead.execute(ran::incrementAndGet);
    bulkhead.execute(ran::incrementAndGet);

    assertThat(started).hasSize(2);
    assertThat(dropped).hasValue(1);

    started.getFirst().run();
    bulkhead.execute(ran::incrementAndGet);

    assertThat(started).hasSize(3);
    assertThat(dropped).hasValue(1);
    assertThat(ran).hasValue(1);
  }

  @Test
  void aJudgingThatFailsStillFreesItsSlot() {
    JudgeBulkhead one = new JudgeBulkhead(1, started::add, dropped::incrementAndGet);
    one.execute(() -> {
      throw new IllegalStateException("boom");
    });
    try {
      started.getFirst().run();
    } catch (IllegalStateException expected) {
      one.execute(ran::incrementAndGet);
    }

    assertThat(started).hasSize(2);
    assertThat(dropped).hasValue(0);
  }

  @Test
  void aJudgeModelThatIsTheReasoningModelKeepsTheJudgeOff() {
    GuideJudge judge =
        GuideConfiguration.guideJudge(
            null,
            new GuideProperties.Judge("nvidia/same", 300, 2),
            "nvidia/same",
            Clock.systemUTC(),
            CallCounter.NONE,
            null,
            Resilience.NONE,
            new SimpleMeterRegistry());

    assertThat(judge).isSameAs(GuideJudge.NONE);
  }
}
