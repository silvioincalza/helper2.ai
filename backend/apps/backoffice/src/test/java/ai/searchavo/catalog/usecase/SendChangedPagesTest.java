package ai.searchavo.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.catalog.Resources;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SendChangedPagesTest {

  private static final Instant NOW = Instant.parse("2026-11-20T03:17:00Z");
  private static final UUID NEW = UUID.fromString("8a0e6c1e-2f43-5b6e-9d0a-1f1e2d3c4b5a");
  private static final UUID OLD = UUID.fromString("9b1f7d2f-3a54-5c7f-8e1b-2a2f3e4d5c6b");

  private final List<List<String>> sent = new ArrayList<>();
  private final List<Collection<String>> askedTypes = new ArrayList<>();

  private SendChangedPages send(boolean on, List<ListIndexableResources.Page> pages) {
    ListIndexableResources resources =
        new ListIndexableResources((Resources) null) {
          @Override
          public List<Page> list(Collection<String> types, int promptMin, int limit) {
            askedTypes.add(types);
            return pages;
          }
        };
    return new SendChangedPages(
        resources,
        sent::add,
        Clock.fixed(NOW, ZoneOffset.UTC),
        "https://searchavo.ai",
        Duration.ofHours(25),
        10_000,
        on);
  }

  @Test
  void sendsTheReadableAddressOfEveryPageChangedInTheWindow() {
    var outcome =
        send(
                true,
                List.of(
                    new ListIndexableResources.Page(NEW, "postgres-mcp", NOW.minusSeconds(3600)),
                    new ListIndexableResources.Page(
                        OLD, "old tool", NOW.minus(Duration.ofDays(3)))))
            .send();

    assertThat(outcome).isEqualTo(new SendChangedPages.Outcome.Sent(1));
    assertThat(sent)
        .containsExactly(List.of("https://searchavo.ai/resource/" + NEW + "/postgres-mcp"));
    assertThat(askedTypes.getFirst()).contains("MCP_SERVER", "PROMPT").doesNotContain("NEWS");
  }

  @Test
  void sendsNothingWhenNothingChanged() {
    var outcome =
        send(
                true,
                List.of(new ListIndexableResources.Page(OLD, "old", NOW.minus(Duration.ofDays(2)))))
            .send();
    assertThat(outcome).isEqualTo(new SendChangedPages.Outcome.NothingChanged());
    assertThat(sent).isEmpty();
  }

  @Test
  void isOffWithoutAKeyAndReadsNothing() {
    assertThat(send(false, List.of()).send()).isEqualTo(new SendChangedPages.Outcome.Off());
    assertThat(askedTypes).isEmpty();
    assertThat(sent).isEmpty();
  }
}
