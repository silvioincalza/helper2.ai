package ai.helper2.sources.github.scout;

import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.scout.DomainRoots;
import ai.helper2.scout.HostLists;
import ai.helper2.scout.HostsNeverRead;
import ai.helper2.scout.ListReading.Followed;
import ai.helper2.scout.PageReader;
import ai.helper2.scout.PageStatus;
import ai.helper2.scout.PageStatusReader;
import ai.helper2.search.ShadowLibraries;
import ai.helper2.search.SourceFailure;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.classifier.ItemPageJudge;
import ai.helper2.sources.liveweb.scout.ScoutLiveWebCredits;
import ai.helper2.sources.npm.scout.NpmPackageReader;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.MissingNode;

/**
 * UC-030 BR-013: follows a link of a list page of a directory host to the page of the item itself,
 * in at most {@link #MAX_STEPS} steps. A step is one request made for the entry: a read of the
 * directory's page of the item or of the page reached, through the live web page reader, or a
 * status request of the Scout's own for a link that redirects (BR-014). The page reached is checked
 * against the name of the entry, by code first and by a model only where code cannot decide. What
 * the follower gives is an address: the rule of its kind decides the rest (BR-006).
 */
public final class ListLinkFollower {

  /** What following gave for a list: its text with the pages found, and what was tried. */
  record Result(AwesomeListParser.Parsed parsed, List<Followed> followed, boolean changed) {}

  static final int MAX_STEPS = 3;

  static final String FEATURE = "scout-follow-links";

  static final String STEPS_FIELD = "follow-steps";

  static final String LINKS = "helper2.scout.follow.links";

  static final String STEPS = "helper2.scout.follow.steps";

  static final String DECIDED = "helper2.scout.follow.decided";

  static final String NOTHING = "helper2.scout.follow.nothing";

  static final String REQUESTS = "helper2.scout.follow.requests";

  static final String WAITS = "helper2.scout.follow.waits";

  static final String BY_CODE = "code";

  static final String BY_MODEL = "model";

  /** Refusals of a status request that was not made: they say nothing about the link. */
  private static final Set<PageStatus.Reason> NOT_MADE =
      EnumSet.of(
          PageStatus.Reason.DAILY_CAP, PageStatus.Reason.HOST_CAP, PageStatus.Reason.NOT_ASKED);

  static final String HOST_CAP_WAIT = "status-" + PageStatus.Reason.HOST_CAP.getTag();

  /** The most links of one page of an item that are looked at. */
  static final int MAX_PAGE_LINKS = 500;

  private static final String READ_DEPTH = "basic";

  private static final String GITHUB = "github.com";

  private static final Pattern MARKDOWN_LINK =
      Pattern.compile("\\[([^\\[\\]]{0,300})\\]\\(\\s{0,5}([^)\\s]{1,2000})[^)]{0,300}\\)");

  private static final Pattern REPOSITORY =
      Pattern.compile("^https://github\\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100})$");

  /** The words a directory writes on the link to the item's own site. */
  private static final Set<String> SITE_WORDS =
      Set.of("visit", "website", "site", "homepage", "official", "open", "launch", "try");

  private static final Logger log = LoggerFactory.getLogger(ListLinkFollower.class);

  /** Where an address stands on the way to the page of the item. */
  private sealed interface Place permits ItemPage, Redirect, Reached, Nowhere {}

  private record ItemPage(CanonicalUrl page) implements Place {}

  private record Redirect(URI address) implements Place {}

  /** {@code repository} is the full name when the page is a repository on GitHub. */
  private record Reached(CanonicalUrl page, @Nullable String repository) implements Place {}

  private record Nowhere(String reason) implements Place {}

  /** One link of a list on its way: the name it carries and the steps it took. */
  private static final class Way {
    private final String host;
    private final String name;
    private final ReadmeBudget budget;
    private int steps;
    private boolean itemPageRead;
    private boolean lastStepTaken;
    private @Nullable String waits;

    Way(String host, String name, ReadmeBudget budget) {
      this.host = host;
      this.name = name;
      this.budget = budget;
    }
  }

  private final ProductLinks links;
  private final DomainRoots domains;
  private final WebPageReader pages;
  private final PageStatusReader statuses;
  private final ItemPageJudge judge;
  private final ScoutLiveWebCredits credits;
  private final QuotaCounter counter;
  private final QuotaKeys keys;
  private final HostsNeverRead neverRead;
  private final ItemPageReads itemPages;
  private final int stepsPerDay;
  private final MeterRegistry meters;

  public ListLinkFollower(
      Collection<String> excludedDomains,
      ShadowLibraries shadowLibraries,
      DomainRoots domains,
      PageReader reader,
      PageStatusReader statuses,
      ItemPageJudge judge,
      ScoutLiveWebCredits credits,
      QuotaCounter counter,
      QuotaKeys keys,
      HostsNeverRead neverRead,
      ItemPageReads itemPages,
      int stepsPerDay,
      MeterRegistry meters) {
    this.links = new ProductLinks(excludedDomains, shadowLibraries);
    this.domains = domains;
    this.pages = new WebPageReader(reader);
    this.statuses = statuses;
    this.judge = judge;
    this.credits = credits.forFeature(FEATURE);
    this.counter = counter;
    this.keys = keys;
    this.neverRead = neverRead;
    this.itemPages = itemPages;
    this.stepsPerDay = stepsPerDay;
    this.meters = meters;
  }

  /** A pass starts: the pages of an item read in the pass before it are forgotten. */
  void passStarted() {
    itemPages.passStarted();
  }

  /**
   * The text of a list with the pages its links were followed to. {@code known} is what following
   * gave since the last read of the list; a link in it is not tried again. With {@code mayTry}
   * false nothing is requested and only what is known is used.
   */
  Result follow(
      CanonicalUrl list,
      AwesomeListParser.Parsed parsed,
      List<Followed> known,
      boolean mayTry,
      HostLists hosts,
      ReadmeBudget budget) {
    Map<String, Followed> followed = new LinkedHashMap<>();
    known.forEach(f -> followed.put(f.getLink(), f));
    boolean changed = false;
    if (mayTry) {
      Set<String> named = entriesThatNameTheirItem(parsed, followed, hosts);
      for (AwesomeListParser.Link link : parsed.links()) {
        if (followed.containsKey(link.url())
            || named.contains(link.entryText())
            || !canBeFollowed(link, list)) {
          continue;
        }
        Way way = new Way(list.getHost(), nameOf(link), budget);
        Followed tried = followOne(link, list, hosts, way);
        if (tried == null) {
          meters.counter(WAITS, "reason", way.waits).increment();
          // The cap of one host stops the links to that host only.
          if (way.waits.equals(HOST_CAP_WAIT)) {
            continue;
          }
          break;
        }
        followed.put(link.url(), tried);
        changed = true;
        if (tried.getItem() != null) {
          named.add(link.entryText());
        }
      }
    }
    List<Followed> all = List.copyOf(followed.values());
    return new Result(withItems(parsed, all), all, changed);
  }

  // UC-030 BR-013: an entry that links its item directly is not followed.
  private Set<String> entriesThatNameTheirItem(
      AwesomeListParser.Parsed parsed, Map<String, Followed> followed, HostLists hosts) {
    Set<String> named = new HashSet<>();
    parsed.entries().forEach(entry -> named.add(entry.text()));
    for (AwesomeListParser.Link link : parsed.links()) {
      CanonicalUrl page = links.page(link.url()).site();
      Followed known = followed.get(link.url());
      boolean direct =
          page != null && !hosts.isDirectory(page.getHost()) && !links.isExcluded(page);
      if (direct || known != null && known.getItem() != null) {
        named.add(link.entryText());
      }
    }
    return named;
  }

  // UC-030 BR-013: only a link to a page of the list's own directory, or a link that redirects,
  // is followed, and only when its words name the item.
  private boolean canBeFollowed(AwesomeListParser.Link link, CanonicalUrl list) {
    if (link.entryText().isBlank() || ItemPageCheck.words(nameOf(link)).isEmpty()) {
      return false;
    }
    ProductLinks.Checked checked = links.page(link.url());
    return checked.skip() == ProductLinks.Skip.REDIRECT
        || checked.site() != null && isPageOfTheDirectory(checked.site(), list);
  }

  private static boolean isPageOfTheDirectory(CanonicalUrl page, CanonicalUrl list) {
    return page.getHost().equals(list.getHost())
        && !page.equals(list)
        && !page.getValue().equals("https://" + page.getHost())
        && page.getValue().length() <= ListEntryLinks.MAX_PAGE_ADDRESS;
  }

  /** The words of the link, when they name something: "website" or "here" names nothing. */
  private static String nameOf(AwesomeListParser.Link link) {
    return ListEntryLinks.namesNothing(link.text()) ? "" : link.text().strip();
  }

  /** Null when a budget or a cap is spent: the link waits and is tried at a later pass. */
  private @Nullable Followed followOne(
      AwesomeListParser.Link link, CanonicalUrl list, HostLists hosts, Way way) {
    meters.counter(LINKS, "host", way.host).increment();
    String address = link.url();
    while (true) {
      String none;
      switch (placeOf(address, list, hosts, way)) {
        case Nowhere nowhere -> {
          return nothing(link, way, nowhere.reason());
        }
        case Reached reached -> {
          return checked(link, reached, way);
        }
        case ItemPage itemPage -> {
          String markdown = read(itemPage.page(), "item-page", way);
          if (markdown == null) {
            return stopped(link, way, "item-page-unreadable");
          }
          itemPages.read(itemPage.page(), markdown);
          way.itemPageRead = true;
          address = linkToTheItem(markdown, itemPage.page(), list, hosts, way);
          none = "no-link-to-the-item";
        }
        case Redirect redirect -> {
          if (!mayStep(way, false)) {
            return stopped(link, way, "no-redirect");
          }
          PageStatus status = statuses.status(redirect.address());
          meters.counter(REQUESTS, "host", way.host, "outcome", outcomeOf(status)).increment();
          // UC-030 BR-013: a request a cap or the open circuit refused was not made: the link
          // waits and no step is counted.
          if (status instanceof PageStatus.Refused refused
              && NOT_MADE.contains(refused.getReason())) {
            way.waits = "status-" + refused.getReason().getTag();
            return null;
          }
          stepTaken(way, "status");
          address = locationOf(status, redirect.address());
          none = "no-redirect";
        }
      }
      if (address == null) {
        return nothing(link, way, none);
      }
    }
  }

  /** A step that was not taken or gave nothing: the link waits, or it gives nothing. */
  private @Nullable Followed stopped(AwesomeListParser.Link link, Way way, String otherwise) {
    if (way.waits != null) {
      return null;
    }
    return nothing(
        link, way, way.steps >= MAX_STEPS && !way.lastStepTaken ? "steps-spent" : otherwise);
  }

  // UC-030 BR-013: every address on the way passes the Scout's host check again.
  private Place placeOf(String address, CanonicalUrl list, HostLists hosts, Way way) {
    ProductLinks.Checked checked = links.page(address);
    if (checked.skip() == ProductLinks.Skip.REDIRECT) {
      return new Redirect(URI.create(address.strip()));
    }
    if (checked.skip() == ProductLinks.Skip.GITHUB) {
      CanonicalUrl page = CanonicalUrl.of(address);
      Matcher repository = REPOSITORY.matcher(page == null ? "" : page.getValue());
      return repository.matches()
          ? new Reached(page, repository.group(1) + "/" + repository.group(2))
          : new Nowhere("not-a-repository");
    }
    CanonicalUrl page = checked.site();
    if (page == null) {
      return new Nowhere(checked.skip().tag());
    }
    if (!way.itemPageRead && isPageOfTheDirectory(page, list)) {
      return new ItemPage(page);
    }
    boolean ofADirectory =
        hosts.isDirectory(page.getHost()) || page.getHost().equals(list.getHost());
    return ofADirectory ? new Nowhere("directory-host") : new Reached(page, null);
  }

  private static @Nullable String locationOf(PageStatus status, URI address) {
    if (!(status instanceof PageStatus.Answered answer)
        || answer.getCode() / 100 != 3
        || answer.getLocation() == null) {
      return null;
    }
    try {
      return address.resolve(answer.getLocation().strip()).toString();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String outcomeOf(PageStatus status) {
    return switch (status) {
      case PageStatus.Answered answer -> answer.getCode() / 100 + "xx";
      case PageStatus.Refused refused -> refused.getReason().getTag();
    };
  }

  /**
   * The link of a directory's page of an item that leads to the item: a link whose address carries
   * the name, else a link whose words say it goes to the site. Links to a directory host are left
   * out; a link that redirects is kept, for the next step.
   */
  private @Nullable String linkToTheItem(
      String markdown, CanonicalUrl itemPage, CanonicalUrl list, HostLists hosts, Way way) {
    String bySiteWords = null;
    Matcher link = MARKDOWN_LINK.matcher(markdown);
    for (int seen = 0; seen < MAX_PAGE_LINKS && link.find(); seen++) {
      String address = absolute(link.group(2), itemPage);
      Place place = address == null ? new Nowhere("malformed") : placeOf(address, list, hosts, way);
      if (place instanceof Reached reached
          && ItemPageCheck.nameInAddress(way.name, reached.page(), siteOf(reached))) {
        return address;
      }
      boolean leadsOn = place instanceof Reached || place instanceof Redirect;
      if (bySiteWords == null && leadsOn && saysItGoesToTheSite(link.group(1), way.name)) {
        bySiteWords = address;
      }
    }
    return bySiteWords;
  }

  private static @Nullable String absolute(String target, CanonicalUrl page) {
    try {
      return URI.create(page.getValue()).resolve(target.strip()).toString();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean saysItGoesToTheSite(String words, String name) {
    List<String> said = ItemPageCheck.words(MarkdownText.plain(words));
    return said.equals(ItemPageCheck.words(name)) || said.stream().anyMatch(SITE_WORDS::contains);
  }

  // UC-030 BR-013: code decides first; the model is asked only where code cannot decide.
  private @Nullable Followed checked(AwesomeListParser.Link link, Reached reached, Way way) {
    if (ItemPageCheck.nameInAddress(way.name, reached.page(), siteOf(reached))) {
      return found(link, reached, way, BY_CODE);
    }
    if (reached.repository() != null || neverRead.refusal(reached.page().getHost()) != null) {
      return nothing(link, way, "name-not-in-address");
    }
    String markdown = read(reached.page(), "own-page", way);
    if (markdown == null) {
      return stopped(link, way, "own-page-unreadable");
    }
    if (ItemPageCheck.nameInTitle(way.name, markdown)) {
      return found(link, reached, way, BY_CODE);
    }
    ItemPageJudge.Answer answer = asked(way.name, markdown);
    meters.counter(DECIDED, "host", way.host, "by", BY_MODEL, "answer", tag(answer)).increment();
    return answer == ItemPageJudge.Answer.YES
        ? new Followed(link.url(), reached.page().getValue(), way.steps, BY_MODEL)
        : nothing(link, way, "model-" + tag(answer));
  }

  // UC-030 BR-013: a model that cannot be asked, or fails, gives no answer.
  private ItemPageJudge.Answer asked(String name, String markdown) {
    try {
      return judge.isPageOf(name, markdown);
    } catch (RuntimeException e) {
      log.info("the check of a followed page gave no answer: {}", e.getClass().getSimpleName());
      return ItemPageJudge.Answer.NONE;
    }
  }

  private static String tag(ItemPageJudge.Answer answer) {
    return answer.name().toLowerCase(Locale.ROOT);
  }

  /**
   * The site of a page that is a site of its own: its registrable domain. Null on a host many items
   * share (a repository, a model, a paper, a package), where only the path names the item.
   */
  private @Nullable String siteOf(Reached reached) {
    CanonicalUrl page = reached.page();
    boolean shared =
        reached.repository() != null
            || page.getHost().equals(GITHUB)
            || page.getHuggingFaceModel() != null
            || page.getArxivPaper() != null
            || NpmPackageReader.nameOf(page) != null;
    if (shared) {
      return null;
    }
    String site = domains.registrable(page.getHost());
    return site == null ? page.getHost() : site;
  }

  private Followed found(AwesomeListParser.Link link, Reached reached, Way way, String by) {
    meters.counter(DECIDED, "host", way.host, "by", by, "answer", "yes").increment();
    return new Followed(link.url(), reached.page().getValue(), way.steps, by);
  }

  private Followed nothing(AwesomeListParser.Link link, Way way, String reason) {
    meters.counter(NOTHING, "host", way.host, "reason", reason).increment();
    return new Followed(link.url(), null, way.steps, null);
  }

  /** The text of a page through the page reader, as one step; null when it gave none. */
  private @Nullable String read(CanonicalUrl page, String kind, Way way) {
    if (!mayStep(way, true)) {
      return null;
    }
    stepTaken(way, kind);
    credits.spent(MissingNode.getInstance(), READ_DEPTH);
    try {
      String markdown = pages.markdown(page.getValue());
      way.budget.read(markdown);
      return markdown;
    } catch (SourceFailure | CallRefused e) {
      log.warn("a page followed from a list on {} is unavailable: {}", way.host, e.getMessage());
      return null;
    }
  }

  // UC-030 BR-013: at most three steps for an entry, inside the steps of the day; a read also
  // needs the page reader and a credit of the Scout.
  private boolean mayStep(Way way, boolean read) {
    way.lastStepTaken = false;
    if (way.steps >= MAX_STEPS) {
      return false;
    }
    way.waits = waitsBecause(way, read);
    return way.waits == null;
  }

  // UC-030 BR-013: every step counts on the steps of the day.
  private void stepTaken(Way way, String kind) {
    way.lastStepTaken = true;
    way.steps++;
    counter.add(stepsOfTheDay(), 1);
    meters.counter(STEPS, "host", way.host, "kind", kind).increment();
  }

  private @Nullable String waitsBecause(Way way, boolean read) {
    String spent = way.budget.spent();
    if (spent != null) {
      return spent;
    }
    try {
      if (counter.used(stepsOfTheDay()) >= stepsPerDay) {
        return "steps-daily-cap";
      }
    } catch (RuntimeException e) {
      log.warn("the steps of the day are unreadable: {}", e.getClass().getSimpleName());
      return "steps-unreadable";
    }
    if (!read) {
      return null;
    }
    if (!pages.available()) {
      return "page-reader-off";
    }
    return credits.capReached() ? "credit-cap" : null;
  }

  private QuotaCounter.Counter stepsOfTheDay() {
    return new QuotaCounter.Counter(keys.global(), STEPS_FIELD);
  }

  /** The list with, next to each followed link, the page of the item it led to. */
  private static AwesomeListParser.Parsed withItems(
      AwesomeListParser.Parsed parsed, List<Followed> followed) {
    Map<String, AwesomeListParser.Link> byUrl = new LinkedHashMap<>();
    parsed.links().forEach(link -> byUrl.putIfAbsent(link.url(), link));
    List<AwesomeListParser.Entry> entries = new ArrayList<>(parsed.entries());
    List<AwesomeListParser.Link> all = new ArrayList<>(parsed.links());
    for (Followed f : followed) {
      AwesomeListParser.Link from = byUrl.get(f.getLink());
      if (from == null || f.getItem() == null) {
        continue;
      }
      Matcher repository = REPOSITORY.matcher(f.getItem());
      if (repository.matches()) {
        String fullName = repository.group(1) + "/" + repository.group(2);
        if (entries.stream().noneMatch(e -> e.fullName().equalsIgnoreCase(fullName))) {
          entries.add(
              new AwesomeListParser.Entry(
                  fullName, from.section(), from.headingPath(), from.entryText(), false));
        }
      } else if (!byUrl.containsKey(f.getItem())) {
        AwesomeListParser.Link item =
            new AwesomeListParser.Link(
                f.getItem(), from.text(), from.section(), from.headingPath(), from.entryText());
        byUrl.put(item.url(), item);
        all.add(item);
      }
    }
    return new AwesomeListParser.Parsed(
        entries, parsed.headings(), all, parsed.textLines(), parsed.linkedItems());
  }
}
