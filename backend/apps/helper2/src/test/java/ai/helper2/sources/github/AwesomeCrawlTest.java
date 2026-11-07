package ai.helper2.sources.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.search.SourceFailure;
import ai.helper2.sources.github.GitHubAwesomeCollector.ListRepo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class AwesomeCrawlTest {

  /** GitHub as a map: facts and READMEs by full name; what is missing is unavailable. */
  static final class FakeGitHub implements AwesomeCrawl.Lists {
    final Map<String, JsonNode> facts = new HashMap<>();
    final Map<String, String> readmes = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    RuntimeException repoFailure;

    @Override
    public JsonNode repo(String fullName) {
      calls.add("repo " + fullName);
      if (repoFailure != null) {
        throw repoFailure;
      }
      JsonNode repo = facts.get(fullName);
      if (repo == null) {
        throw new SourceFailure.Unavailable("404");
      }
      return repo;
    }

    @Override
    public String readme(String fullName) {
      calls.add("readme " + fullName);
      String readme = readmes.get(fullName);
      if (readme == null) {
        throw new SourceFailure.Unavailable("404");
      }
      return readme;
    }

    /** A live AI list with enough stars, linking [links]. */
    FakeGitHub list(String fullName, String... links) {
      facts.put(fullName, facts(fullName, "Curated LLM tools", false, false, 500));
      StringBuilder readme = new StringBuilder("# " + fullName + "\n## Tools\n");
      for (String link : links) {
        readme.append("- [x](https://github.com/").append(link).append(")\n");
      }
      readmes.put(fullName, readme.toString());
      return this;
    }
  }

  static ObjectNode facts(
      String fullName, String description, boolean archived, boolean fork, long stars) {
    ObjectNode repo = JsonNodeFactory.instance.objectNode();
    repo.put("full_name", fullName);
    repo.put("html_url", "https://github.com/" + fullName);
    repo.put("description", description);
    repo.put("archived", archived);
    repo.put("fork", fork);
    repo.put("stargazers_count", stars);
    return repo;
  }

  /** A clock that moves [step] each time it is read. */
  static final class StepClock extends Clock {
    Instant now = Instant.parse("2026-11-06T10:00:00Z");
    final Duration step;

    StepClock(Duration step) {
      this.step = step;
    }

    @Override
    public Instant instant() {
      Instant read = now;
      now = now.plus(step);
      return read;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  final FakeGitHub github = new FakeGitHub();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  AwesomeCrawl.Limits limits(int depth, int nested) {
    return new AwesomeCrawl.Limits(depth, nested, Duration.ofMinutes(15), 50_000_000L, 50);
  }

  List<AwesomeCrawl.Read> crawl(AwesomeCrawl.Limits limits, Clock clock, String... seeds) {
    List<ListRepo> lists = new ArrayList<>();
    for (String seed : seeds) {
      lists.add(new ListRepo(seed, github.facts.get(seed)));
    }
    return AwesomeCrawl.crawl(lists, github, limits, clock, new AwesomeMetrics(meters));
  }

  List<AwesomeCrawl.Read> crawl(AwesomeCrawl.Limits limits, String... seeds) {
    return crawl(limits, Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC), seeds);
  }

  double skips(String reason) {
    var counter = meters.find(AwesomeMetrics.SKIPS).tag("reason", reason).counter();
    return counter == null ? 0 : counter.count();
  }

  static List<String> names(List<AwesomeCrawl.Read> read) {
    return read.stream().map(r -> r.list().fullName() + "@" + r.depth()).toList();
  }

  @Test
  void followsLinkedListsTwoStepsFromASeedAndNoFurther() {
    github
        .list("seed/awesome-llm", "a/awesome-one", "tool/plain")
        .list("a/awesome-one", "b/awesome-two")
        .list("b/awesome-two", "c/awesome-three")
        .list("c/awesome-three");

    List<AwesomeCrawl.Read> read = crawl(limits(2, 30), "seed/awesome-llm");

    assertThat(names(read))
        .containsExactly("seed/awesome-llm@0", "a/awesome-one@1", "b/awesome-two@2");
    assertThat(github.calls).noneMatch(c -> c.contains("awesome-three"));
    assertThat(skips("nested-depth")).isEqualTo(1);
    assertThat(meters.find(AwesomeMetrics.LISTS).tag("depth", "nested").counter().count())
        .isEqualTo(2);
  }

  @Test
  void depthZeroReadsTheSeedsOnly() {
    github.list("seed/awesome-llm", "a/awesome-one").list("a/awesome-one");

    assertThat(names(crawl(limits(0, 30), "seed/awesome-llm")))
        .containsExactly("seed/awesome-llm@0");
  }

  @Test
  void aCycleOrASecondLinkReadsEachListOnce() {
    github
        .list("seed/awesome-llm", "a/awesome-one", "b/awesome-two")
        .list("a/awesome-one", "seed/awesome-llm", "b/awesome-two", "a/awesome-one")
        .list("b/awesome-two", "a/awesome-one", "SEED/Awesome-LLM");

    List<AwesomeCrawl.Read> read = crawl(limits(2, 30), "seed/awesome-llm");

    assertThat(names(read))
        .containsExactly("seed/awesome-llm@0", "a/awesome-one@1", "b/awesome-two@1");
    assertThat(github.calls).filteredOn(c -> c.startsWith("readme")).hasSize(3);
    // a -> seed, a -> b, b -> a, b -> seed (the parser drops a list's link to itself).
    assertThat(skips("nested-seen")).isEqualTo(4);
  }

  @Test
  void aRenamedListThatWasAlreadyReadIsNotReadAgain() {
    github.list("seed/awesome-llm", "old/awesome-name", "new/awesome-name");
    github.list("new/awesome-name");
    github.facts.put("old/awesome-name", github.facts.get("new/awesome-name"));

    List<AwesomeCrawl.Read> read = crawl(limits(2, 30), "seed/awesome-llm");

    assertThat(names(read)).containsExactly("seed/awesome-llm@0", "new/awesome-name@1");
  }

  @Test
  void triesAtMostTheNestedCapPerRun() {
    github
        .list("seed/awesome-llm", "a/awesome-1", "a/awesome-2", "a/awesome-3")
        .list("a/awesome-1")
        .list("a/awesome-2")
        .list("a/awesome-3");

    List<AwesomeCrawl.Read> read = crawl(limits(2, 2), "seed/awesome-llm");

    assertThat(names(read)).hasSize(3).doesNotContain("a/awesome-3@1");
    assertThat(skips("nested-cap")).isEqualTo(1);
  }

  @Test
  void aNestedListMustBeAliveAndAboutAi() {
    github.list(
        "seed/awesome-llm",
        "x/awesome-archived",
        "x/awesome-fork",
        "x/awesome-small",
        "x/awesome-python",
        "x/awesome-gone");
    github.facts.put(
        "x/awesome-archived", facts("x/awesome-archived", "LLM tools", true, false, 500));
    github.facts.put("x/awesome-fork", facts("x/awesome-fork", "LLM tools", false, true, 500));
    github.facts.put("x/awesome-small", facts("x/awesome-small", "LLM tools", false, false, 3));
    github.facts.put(
        "x/awesome-python", facts("x/awesome-python", "Python frameworks", false, false, 900));

    List<AwesomeCrawl.Read> read = crawl(limits(2, 30), "seed/awesome-llm");

    assertThat(names(read)).containsExactly("seed/awesome-llm@0");
    assertThat(skips("nested-archived")).isEqualTo(1);
    assertThat(skips("nested-fork")).isEqualTo(1);
    assertThat(skips("nested-few-stars")).isEqualTo(1);
    assertThat(skips("nested-not-ai")).isEqualTo(1);
    assertThat(skips("nested-unavailable")).isEqualTo(1);
  }

  @Test
  void aRateLimitOnANestedListKeepsWhatWasRead() {
    github.list("seed/awesome-llm", "a/awesome-one", "b/awesome-two");
    github.repoFailure = new SourceFailure.RateLimited(null);

    List<AwesomeCrawl.Read> read = crawl(limits(2, 30), "seed/awesome-llm");

    assertThat(names(read)).containsExactly("seed/awesome-llm@0");
    assertThat(skips("rate-limited")).isEqualTo(2);
  }

  @Test
  void aRateLimitOnASeedStopsThePass() {
    AwesomeCrawl.Lists limited =
        new AwesomeCrawl.Lists() {
          @Override
          public JsonNode repo(String fullName) {
            throw new AssertionError("a seed has its facts");
          }

          @Override
          public String readme(String fullName) {
            throw new SourceFailure.RateLimited(null);
          }
        };

    assertThatThrownBy(() ->
            AwesomeCrawl.crawl(
                List.of(new ListRepo("seed/awesome-llm", null)),
                limited,
                limits(2, 30),
                Clock.systemUTC(),
                new AwesomeMetrics(meters)))
        .isInstanceOf(SourceFailure.RateLimited.class);
  }

  @Test
  void stopsReadingAtTheReadmeByteBudget() {
    github.list("seed/one", "a/awesome-one").list("seed/two").list("a/awesome-one");

    List<AwesomeCrawl.Read> read =
        crawl(
            new AwesomeCrawl.Limits(2, 30, Duration.ofMinutes(15), 10, 50), "seed/one", "seed/two");

    assertThat(names(read)).containsExactly("seed/one@0");
    assertThat(skips("size-budget")).isEqualTo(2);
  }

  @Test
  void stopsReadingAtTheTimeBudget() {
    github.list("seed/one").list("seed/two").list("seed/three");

    // The deadline is read once, then once per list: 10 minutes pass between reads.
    List<AwesomeCrawl.Read> read =
        crawl(
            new AwesomeCrawl.Limits(2, 30, Duration.ofMinutes(25), 50_000_000L, 50),
            new StepClock(Duration.ofMinutes(10)),
            "seed/one",
            "seed/two",
            "seed/three");

    assertThat(names(read)).containsExactly("seed/one@0", "seed/two@0");
    assertThat(skips("time-budget")).isEqualTo(1);
  }

  @Test
  void aSeedWhoseReadmeIsUnavailableIsSkipped() {
    github.list("seed/two");

    assertThat(names(crawl(limits(2, 30), "seed/gone", "seed/two"))).containsExactly("seed/two@0");
    assertThat(skips("readme-unavailable")).isEqualTo(1);
  }
}
