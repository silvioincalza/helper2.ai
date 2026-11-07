package ai.helper2.sources.github;

import ai.helper2.search.SourceFailure;
import ai.helper2.sources.github.GitHubAwesomeCollector.ListRepo;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Reads the seed lists and follows the awesome lists they link to (ADR 0022), breadth first: every
 * seed, then the lists they link, then the lists those link, up to [maxDepth] steps from a seed.
 * Each list is read once (a cycle or a second link to it is skipped), at most [maxNestedLists]
 * nested lists are tried per run, and the whole read stops at [maxRunTime] or [maxReadmeBytes].
 *
 * <p>A nested list is followed only when its GitHub facts say it is alive and about AI: not
 * archived, not a fork, at least [minStars] stars, and AI words in its name, description or topics.
 * A rate limit on a nested list stops the following and keeps what was read; on a seed it stops the
 * pass, as before.
 */
final class AwesomeCrawl {

  private static final Logger log = LoggerFactory.getLogger(AwesomeCrawl.class);

  /** Where a list's facts and README come from: the collector's GitHub calls. */
  interface Lists {
    /** The repository's facts (GitHub REST); throws {@link SourceFailure} when unavailable. */
    JsonNode repo(String fullName);

    /** The README as raw Markdown; throws {@link SourceFailure} when unavailable. */
    String readme(String fullName);
  }

  record Limits(
      int maxDepth, int maxNestedLists, Duration maxRunTime, long maxReadmeBytes, int minStars) {}

  /** One list read: its facts, its README parsed, and its steps from a seed (0 for a seed). */
  record Read(ListRepo list, AwesomeListParser.Parsed parsed, int depth) {}

  private record Pending(String fullName, @Nullable JsonNode facts, int depth) {}

  private final Lists lists;
  private final Limits limits;
  private final Clock clock;
  private final AwesomeMetrics metrics;

  private final Deque<Pending> queue = new ArrayDeque<>();
  private final Set<String> known = new HashSet<>();
  private final List<Read> read = new ArrayList<>();
  private int nestedTried;
  private long bytes;

  private AwesomeCrawl(Lists lists, Limits limits, Clock clock, AwesomeMetrics metrics) {
    this.lists = lists;
    this.limits = limits;
    this.clock = clock;
    this.metrics = metrics;
  }

  /**
   * The lists read in this run, seeds first, then each step's lists in the order they were found.
   */
  static List<Read> crawl(
      List<ListRepo> seeds, Lists lists, Limits limits, Clock clock, AwesomeMetrics metrics) {
    AwesomeCrawl crawl = new AwesomeCrawl(lists, limits, clock, metrics);
    crawl.run(seeds);
    return List.copyOf(crawl.read);
  }

  private void run(List<ListRepo> seeds) {
    for (ListRepo seed : seeds) {
      if (known.add(key(seed.fullName()))) {
        queue.add(new Pending(seed.fullName(), seed.facts(), 0));
      }
    }
    Instant deadline = clock.instant().plus(limits.maxRunTime());
    while (!queue.isEmpty()) {
      String over = overBudget(deadline);
      if (over != null) {
        log.info("awesome lists: {} lists left unread ({})", queue.size(), over);
        metrics.skipped(over, queue.size());
        return;
      }
      Pending next = queue.poll();
      Read list;
      try {
        list = read(next);
      } catch (SourceFailure.RateLimited e) {
        if (next.depth() == 0) {
          throw e;
        }
        log.warn("awesome lists: rate limited, {} nested lists left", queue.size() + 1);
        metrics.skipped("rate-limited", queue.size() + 1);
        return;
      }
      if (list != null) {
        read.add(list);
        metrics.listRead(list.depth());
        queueNested(list);
      }
    }
  }

  private @Nullable String overBudget(Instant deadline) {
    if (bytes >= limits.maxReadmeBytes()) {
      return "size-budget";
    }
    return clock.instant().isBefore(deadline) ? null : "time-budget";
  }

  /** The list's README, parsed; null when it is skipped (its reason counted). */
  private @Nullable Read read(Pending next) {
    ListRepo list = next.depth() == 0 ? new ListRepo(next.fullName(), next.facts()) : nested(next);
    if (list == null) {
      return null;
    }
    String readme;
    try {
      readme = lists.readme(list.fullName());
    } catch (SourceFailure.Unavailable e) {
      log.warn("awesome list {} README unavailable: {}", list.fullName(), e.getMessage());
      metrics.skipped("readme-unavailable");
      return null;
    }
    bytes += readme.getBytes(StandardCharsets.UTF_8).length;
    return new Read(list, AwesomeListParser.parse(readme, list.fullName()), next.depth());
  }

  /** A nested list with its facts, or null when it is not followed (its reason counted). */
  private @Nullable ListRepo nested(Pending next) {
    JsonNode repo;
    try {
      repo = lists.repo(next.fullName());
    } catch (SourceFailure.Unavailable e) {
      metrics.skipped("nested-unavailable");
      return null;
    }
    String reason = notFollowed(repo);
    if (reason != null) {
      metrics.skipped(reason);
      return null;
    }
    String name = HttpClients.text(repo, "full_name");
    if (name == null || !name.contains("/")) {
      metrics.skipped("nested-unavailable");
      return null;
    }
    // A renamed repository answers with its new name, which may be a list already read.
    if (!key(name).equals(key(next.fullName())) && !known.add(key(name))) {
      metrics.skipped("nested-seen");
      return null;
    }
    return new ListRepo(name, repo);
  }

  private @Nullable String notFollowed(JsonNode repo) {
    if (repo.path("archived").asBoolean(false)) {
      return "nested-archived";
    }
    if (repo.path("fork").asBoolean(false)) {
      return "nested-fork";
    }
    if (repo.path("stargazers_count").asLong(0) < limits.minStars()) {
      return "nested-few-stars";
    }
    List<String> topics = new ArrayList<>();
    repo.path("topics").forEach(t -> topics.add(t.asString("")));
    if (!Classification.isAiRelated(
        HttpClients.text(repo, "full_name"), HttpClients.text(repo, "description"), topics)) {
      return "nested-not-ai";
    }
    return null;
  }

  /** The lists [list] links to, each once, while the depth and the per-run cap allow. */
  private void queueNested(Read list) {
    for (AwesomeListParser.Entry e : list.parsed().entries()) {
      if (!e.pointsAtList()) {
        continue;
      }
      if (list.depth() >= limits.maxDepth()) {
        metrics.skipped("nested-depth");
      } else if (!known.add(key(e.fullName()))) {
        metrics.skipped("nested-seen");
      } else if (nestedTried >= limits.maxNestedLists()) {
        metrics.skipped("nested-cap");
      } else {
        nestedTried++;
        queue.add(new Pending(e.fullName(), null, list.depth() + 1));
      }
    }
  }

  private static String key(String fullName) {
    return fullName.toLowerCase(Locale.ROOT);
  }
}
