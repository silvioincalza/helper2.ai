package ai.helper2.catalog.usecase;

import ai.helper2.catalog.IndexRule;
import ai.helper2.catalog.ResourcePathsKt;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class SendChangedPages {

  public interface Notice {
    void send(List<String> urls);
  }

  public sealed interface Outcome {
    record Off() implements Outcome {}

    record NothingChanged() implements Outcome {}

    record Sent(int urls) implements Outcome {}
  }

  private final ListIndexableResources resources;
  private final Notice notice;
  private final Clock clock;
  private final String base;
  private final Duration window;
  private final int batch;
  private final boolean on;

  public SendChangedPages(
      ListIndexableResources resources,
      Notice notice,
      Clock clock,
      String publicBaseUrl,
      Duration window,
      int batch,
      boolean on) {
    this.resources = resources;
    this.notice = notice;
    this.clock = clock;
    this.base = publicBaseUrl;
    this.window = window;
    this.batch = batch;
    this.on = on;
  }

  public Outcome send() {
    if (!on) {
      return new Outcome.Off();
    }
    List<String> urls = changedSince(clock.instant().minus(window));
    if (urls.isEmpty()) {
      return new Outcome.NothingChanged();
    }
    notice.send(urls);
    return new Outcome.Sent(urls.size());
  }

  private List<String> changedSince(Instant since) {
    return resources.list(IndexRule.listedTypeNames(), IndexRule.PROMPT_MIN_LENGTH, batch).stream()
        .filter(page -> page.lastModified().isAfter(since))
        .map(page -> base + ResourcePathsKt.resourcePath(page.id(), page.title()))
        .toList();
  }
}
