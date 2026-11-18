package ai.helper2.research.internal;

import static org.assertj.core.api.Assumptions.assumeThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.research.CheckResearch;
import ai.helper2.research.ResearchFilter;
import ai.helper2.research.ResearchItems;
import ai.helper2.research.ResearchModel;
import ai.helper2.research.ResearchResult;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchStep;
import ai.helper2.research.ResearchSubject;
import ai.helper2.research.ResearchTool;
import ai.helper2.research.config.ResearchProperties;
import ai.helper2.research.usecase.RunResearch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Tag("eval-record")
@SpringBootTest(properties = {"helper2.research.concurrency=0", "helper2.scout.enabled=false"})
@Import(TestcontainersConfiguration.class)
class ResearchEvalRecorder {

  static final Path REPORTS = Path.of("../docs/eval");
  static final Path TRACES = Path.of("eval-data/research-traces");

  @Autowired ResearchModel model;
  @Autowired List<ResearchTool> tools;
  @Autowired ResearchProperties properties;

  @Test
  void researchesEveryLabelledItemAndWritesTheReport() throws IOException {
    Set<String> names = tools.stream().map(ResearchTool::name).collect(Collectors.toSet());
    assumeThat(model.model())
        .as("a configured research model (H2_NEBIUS_API_KEY)")
        .isNotEqualTo("none");
    assumeThat(names).as("live tools (H2_LIVE_WEB_API_KEY)").contains("read_page", "web_search");
    ResearchLabels labels = ResearchLabels.readAll();
    String only = System.getenv("H2_EVAL_ONLY");
    List<ResearchLabels.Label> chosen =
        labels.labels().stream()
            .filter(l -> only == null || List.of(only.split(",")).contains(l.id()))
            .toList();
    String name = properties.model().name().toLowerCase(Locale.ROOT);
    Path traces = TRACES.resolve(name);
    Files.createDirectories(traces);
    Duration pause =
        Duration.parse(Optional.ofNullable(System.getenv("H2_EVAL_PAUSE")).orElse("PT2S"));
    List<ResearchEvalScore.Outcome> outcomes = new ArrayList<>();
    for (ResearchLabels.Label label : chosen) {
      if (!outcomes.isEmpty()) {
        sleep(pause);
      }
      OneRun runs = new OneRun(label.itemId(), model.model());
      RunResearch research =
          new RunResearch(
              runs,
              new LabelItems(label),
              model,
              tools,
              new CheckResearch(),
              ResearchConfiguration.prompt(),
              ResearchConfiguration::subjectText,
              Clock.systemUTC());
      long started = System.nanoTime();
      research.runNext();
      long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
      outcomes.add(runs.outcome(label, millis));
      Files.writeString(traces.resolve(label.id() + ".txt"), runs.trace(), StandardCharsets.UTF_8);
      System.out.println(label.id() + " " + runs.status + " " + millis + " ms " + label.url());
    }
    ResearchEvalScore.Report report = ResearchEvalScore.score(model.model(), outcomes);
    Files.writeString(
        REPORTS.resolve("research-report-" + name + ".md"),
        ResearchEvalScore.markdown(report),
        StandardCharsets.UTF_8);
    Files.writeString(
        REPORTS.resolve("research-baseline-" + name + ".json"),
        ResearchEvalScore.json(report),
        StandardCharsets.UTF_8);
  }

  private static void sleep(Duration pause) {
    try {
      Thread.sleep(pause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private record LabelItems(ResearchLabels.Label label) implements ResearchItems {

    @Override
    public Optional<ResearchSubject> subject(UUID itemId) {
      return Optional.of(
          new ResearchSubject(
              label.itemId(),
              label.kind(),
              label.url(),
              label.titleFound(),
              label.typeFound(),
              List.of(),
              null));
    }

    @Override
    public int openResearchedDone() {
      return 0;
    }

    @Override
    public List<UUID> openNotResearched(int limit) {
      return List.of(label.itemId());
    }
  }

  private static final class OneRun implements ResearchRuns {

    private final ResearchRun run;
    private final List<ResearchStep> steps = new ArrayList<>();
    private boolean claimed;
    private ResearchRun.Status status = ResearchRun.Status.QUEUED;
    private @Nullable ResearchResult result;
    private @Nullable String error;
    private int prompt;
    private int completion;

    OneRun(UUID itemId, String model) {
      this.run =
          new ResearchRun(
              UUID.randomUUID(),
              itemId,
              ResearchRun.Status.RUNNING,
              model,
              "eval",
              Instant.now(),
              Instant.now(),
              null,
              0,
              0,
              0,
              null,
              null);
    }

    ResearchEvalScore.Outcome outcome(ResearchLabels.Label label, long millis) {
      int calls =
          (int) steps.stream().filter(s -> s.kind() == ResearchStep.Kind.MODEL_CALL).count();
      return new ResearchEvalScore.Outcome(
          label, status, result, error, millis, calls, prompt, completion);
    }

    String trace() {
      return steps.stream()
          .map(s ->
              "#"
                  + s.seq()
                  + " "
                  + s.kind()
                  + " "
                  + s.name()
                  + " "
                  + s.durationMs()
                  + " ms\n--- request\n"
                  + s.request()
                  + "\n--- response\n"
                  + (s.response() == null ? "" : s.response())
                  + (s.error() == null ? "" : "\n--- error\n" + s.error())
                  + "\n")
          .collect(Collectors.joining("\n"));
    }

    @Override
    public List<ResearchRun> enqueue(Collection<UUID> itemIds, String model, String by) {
      return List.of(run);
    }

    @Override
    public Set<UUID> activeItems() {
      return status.active() ? Set.of(run.itemId()) : Set.of();
    }

    @Override
    public Set<UUID> doneItems() {
      return status == ResearchRun.Status.DONE ? Set.of(run.itemId()) : Set.of();
    }

    @Override
    public boolean requeue(UUID runId) {
      status = ResearchRun.Status.QUEUED;
      steps.clear();
      return true;
    }

    @Override
    public Optional<ResearchRun> claimNext(Instant now) {
      if (claimed) {
        return Optional.empty();
      }
      claimed = true;
      status = ResearchRun.Status.RUNNING;
      return Optional.of(run);
    }

    @Override
    public void step(ResearchStep step) {
      steps.add(step);
    }

    @Override
    public void done(
        UUID runId, ResearchResult result, int promptTokens, int completionTokens, Instant at) {
      this.status = ResearchRun.Status.DONE;
      this.result = result;
      this.prompt = promptTokens;
      this.completion = completionTokens;
    }

    @Override
    public void failed(
        UUID runId, String error, int promptTokens, int completionTokens, Instant at) {
      this.status = ResearchRun.Status.FAILED;
      this.error = error;
      this.prompt = promptTokens;
      this.completion = completionTokens;
    }

    @Override
    public boolean cancel(UUID runId, Instant at) {
      return false;
    }

    @Override
    public boolean isCancelled(UUID runId) {
      return false;
    }

    @Override
    public int failStale(Instant before, Instant at) {
      return 0;
    }

    @Override
    public Optional<ResearchRun> find(UUID runId) {
      return Optional.of(run);
    }

    @Override
    public List<ResearchRun> latest(Collection<UUID> itemIds) {
      return List.of(run);
    }

    @Override
    public List<ResearchStep> steps(UUID runId, int afterSeq) {
      return steps.stream().filter(s -> s.seq() > afterSeq).toList();
    }

    @Override
    public int purge(Instant before) {
      return 0;
    }

    @Override
    public Set<UUID> itemsWhere(ResearchFilter filter) {
      return Set.of();
    }
  }
}
