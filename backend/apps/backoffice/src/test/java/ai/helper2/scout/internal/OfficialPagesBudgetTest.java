package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.llm.CallCounter;
import ai.helper2.scout.OfficialPages;
import ai.helper2.scout.PageReader;
import ai.helper2.scout.PageTextCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OfficialPagesBudgetTest {

  final Clock clock = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final PageReader reader = urls -> List.of();

  private OfficialPages pages(PageReader reader, AtomicInteger dailyCredits) {
    return ScoutConfiguration.officialPages(
        reader,
        host -> host,
        null,
        null,
        PageTextCache.NONE,
        dailyCredits::get,
        Duration.ofDays(30),
        clock,
        CallCounter.NONE,
        meters);
  }

  private double creditsLeft() {
    return meters.get("helper2.scout.evidence.credits.remaining").gauge().value();
  }

  // UC-028 BR-002: 0 turns the page reads off, it does not remove the page step.
  @Test
  void aLimitOfZeroAtStartKeepsThePageStepAndARaiseAtRuntimeOpensIt() {
    AtomicInteger dailyCredits = new AtomicInteger(0);

    assertThat(pages(reader, dailyCredits)).isNotNull();
    assertThat(creditsLeft()).isZero();

    dailyCredits.set(3_000);
    assertThat(creditsLeft()).isEqualTo(3_000);
  }

  @Test
  void withoutAReaderThereIsNoPageStep() {
    assertThat(pages(null, new AtomicInteger(3_000))).isNull();
  }
}
