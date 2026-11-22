package ai.helper2.sources.pagestatus;

import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.HostsNeverRead;
import ai.helper2.scout.PageStatus;
import ai.helper2.scout.PageStatus.Reason;
import ai.helper2.scout.PageStatusReader;
import ai.helper2.search.SourceFailure;
import ai.helper2.shared.WebAddresses;
import ai.helper2.sources.research.PublicAddresses;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hc.client5.http.impl.classic.RequestFailedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UC-030 BR-014: the Scout's own status request to a site. It asks for the status of one address
 * (HEAD; GET only when the site refuses HEAD, and then no body is read), follows no redirect, and
 * is made only when the address passes the Scout's host check, the host is not one never read, its
 * robots.txt allows the path, the caps of the day have room and every address the name resolves to
 * is public. The content of a page never comes through it.
 */
public final class LivePageStatusReader implements PageStatusReader {

  public static final String POLICY = "page-status";

  /** How many requests a UTC day may make in all, and to one host. */
  public record Limits(int requestsPerDay, int requestsPerHostPerDay) {}

  interface Transport {
    Answer send(String method, URI url, List<InetAddress> checked, int maxBodyBytes)
        throws IOException;
  }

  record Answer(int status, @Nullable String location, String body) {}

  static final int MAX_ROBOTS_BYTES = 512 * 1024;

  static final Duration ROBOTS_KEPT = Duration.ofDays(1);

  static final int MAX_ROBOTS_HOSTS = 1_000;

  static final String DAY_FIELD = "page-status";

  /** Refusals of the robots.txt request that say nothing about what the site allows. */
  private static final Set<Reason> NOT_ABOUT_ROBOTS =
      EnumSet.of(Reason.DAILY_CAP, Reason.HOST_CAP, Reason.PRIVATE_ADDRESS, Reason.NOT_ASKED);

  private static final Logger log = LoggerFactory.getLogger(LivePageStatusReader.class);

  private record Robots(RobotsRules rules, Instant until) {}

  /** What one request gave: an answer, or the reason there is none. */
  private record Tried(@Nullable Answer answer, @Nullable Reason refused) {}

  private final PublicAddresses addresses;
  private final HostsNeverRead neverRead;
  private final Transport transport;
  private final Resilience resilience;
  private final QuotaCounter counter;
  private final QuotaKeys keys;
  private final Limits limits;
  private final Clock clock;
  private final Map<String, Robots> robots =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Robots> eldest) {
          return size() > MAX_ROBOTS_HOSTS;
        }
      };

  public LivePageStatusReader(
      PublicAddresses addresses,
      HostsNeverRead neverRead,
      Resilience resilience,
      QuotaCounter counter,
      QuotaKeys keys,
      Limits limits,
      Clock clock) {
    this(addresses, neverRead, new PinnedTransport(), resilience, counter, keys, limits, clock);
  }

  LivePageStatusReader(
      PublicAddresses addresses,
      HostsNeverRead neverRead,
      Transport transport,
      Resilience resilience,
      QuotaCounter counter,
      QuotaKeys keys,
      Limits limits,
      Clock clock) {
    resilience.require(POLICY);
    this.addresses = addresses;
    this.neverRead = neverRead;
    this.transport = transport;
    this.resilience = resilience;
    this.counter = counter;
    this.keys = keys;
    this.limits = limits;
    this.clock = clock;
  }

  @Override
  public synchronized PageStatus status(URI address) {
    // UC-030 BR-014: the Scout's host check comes before any request, for every address.
    if (!(WebAddresses.check(address) instanceof WebAddresses.Checked.Public page)) {
      return new PageStatus.Refused(Reason.HOST_CHECK);
    }
    URI url = page.getUri();
    String host = page.getHost();
    if (neverRead.refusal(host) != null) {
      return new PageStatus.Refused(Reason.NEVER_READ);
    }
    if (addresses.urlProblem(url) != null) {
      return new PageStatus.Refused(Reason.HOST_CHECK);
    }
    Reason robotsRefusal = refusedByRobots(host, url);
    if (robotsRefusal != null) {
      return new PageStatus.Refused(robotsRefusal);
    }
    Tried head = request("HEAD", url, host, 0);
    // A site that refuses HEAD is asked with GET; its body is not read.
    Tried tried =
        head.answer() != null && refusesHead(head.answer()) ? request("GET", url, host, 0) : head;
    return tried.answer() == null
        ? new PageStatus.Refused(tried.refused())
        : new PageStatus.Answered(tried.answer().status(), tried.answer().location());
  }

  private static boolean refusesHead(Answer answer) {
    return answer.status() == 405 || answer.status() == 501;
  }

  // UC-030 BR-014: robots.txt is honoured for the path asked and kept a day per host. A site with
  // no robots.txt (404, 410) states no rule; a robots.txt that cannot be read allows nothing.
  private @Nullable Reason refusedByRobots(String host, URI url) {
    Instant now = clock.instant();
    Robots known = robots.get(host);
    if (known == null || !now.isBefore(known.until())) {
      Tried read =
          request("GET", URI.create("https://" + host + "/robots.txt"), host, MAX_ROBOTS_BYTES);
      URI twin = read.answer() == null ? null : wwwTwin(host, read.answer());
      if (twin != null) {
        read = request("GET", twin, twin.getHost(), MAX_ROBOTS_BYTES);
      }
      // A cap, a private address or an open circuit says nothing about the rules: not kept.
      if (NOT_ABOUT_ROBOTS.contains(read.refused())) {
        return read.refused();
      }
      known = new Robots(rulesOf(read), now.plus(ROBOTS_KEPT));
      robots.put(host, known);
    }
    String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
    String asked = url.getRawQuery() == null ? path : path + "?" + url.getRawQuery();
    return known.rules().allows(asked) ? null : Reason.ROBOTS;
  }

  private static RobotsRules rulesOf(Tried read) {
    Answer answer = read.answer();
    if (answer == null) {
      return RobotsRules.DISALLOW_ALL;
    }
    if (answer.status() == 200) {
      return RobotsRules.parse(answer.body());
    }
    boolean none = answer.status() == 404 || answer.status() == 410;
    return none ? RobotsRules.ALLOW_ALL : RobotsRules.DISALLOW_ALL;
  }

  /** The robots.txt of the same site with or without {@code www.}, when a redirect names it. */
  private static @Nullable URI wwwTwin(String host, Answer answer) {
    if (answer.status() / 100 != 3 || answer.location() == null) {
      return null;
    }
    String twin = host.startsWith("www.") ? host.substring(4) : "www." + host;
    String wanted = "https://" + twin + "/robots.txt";
    boolean named = answer.location().strip().equals(wanted);
    return named && WebAddresses.check(wanted) instanceof WebAddresses.Checked.Public
        ? URI.create(wanted)
        : null;
  }

  private Tried request(String method, URI url, String host, int maxBodyBytes) {
    Reason full = capReached(host);
    if (full != null) {
      return new Tried(null, full);
    }
    List<InetAddress> checked;
    try {
      checked = addresses.resolve(host);
    } catch (PublicAddresses.NotPublic e) {
      return new Tried(null, Reason.PRIVATE_ADDRESS);
    }
    try {
      return new Tried(
          resilience.call(POLICY, () -> send(method, url, checked, maxBodyBytes)), null);
    } catch (UncheckedIOException e) {
      log.info(
          "a status request to {} gave no answer: {}",
          host,
          e.getCause().getClass().getSimpleName());
      return new Tried(null, timedOut(e.getCause()) ? Reason.TIMEOUT : Reason.UNAVAILABLE);
    } catch (SourceFailure | CallRefused e) {
      log.info("a status request to {} was not made: {}", host, e.getClass().getSimpleName());
      return new Tried(null, Reason.NOT_ASKED);
    }
  }

  private static boolean timedOut(IOException e) {
    return e instanceof SocketTimeoutException || e instanceof RequestFailedException;
  }

  private Answer send(String method, URI url, List<InetAddress> checked, int maxBodyBytes) {
    try {
      return transport.send(method, url, checked, maxBodyBytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // UC-030 BR-014: every try counts on the day and on its host, before the request is made.
  private @Nullable Reason capReached(String host) {
    QuotaCounter.Counter day = new QuotaCounter.Counter(keys.global(), DAY_FIELD);
    QuotaCounter.Counter ofHost = new QuotaCounter.Counter(keys.global(), DAY_FIELD + ":" + host);
    try {
      if (counter.used(day) >= limits.requestsPerDay()) {
        return Reason.DAILY_CAP;
      }
      if (counter.used(ofHost) >= limits.requestsPerHostPerDay()) {
        return Reason.HOST_CAP;
      }
      counter.add(day, 1);
      counter.add(ofHost, 1);
      return null;
    } catch (RuntimeException e) {
      log.warn("the caps of status requests are unreadable: {}", e.getClass().getSimpleName());
      return Reason.DAILY_CAP;
    }
  }
}
