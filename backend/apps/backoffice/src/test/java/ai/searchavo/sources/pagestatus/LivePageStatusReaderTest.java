package ai.searchavo.sources.pagestatus;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.platform.quota.QuotaCounter;
import ai.searchavo.platform.quota.QuotaKeys;
import ai.searchavo.platform.resilience.CallRefused;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.HostsNeverRead;
import ai.searchavo.scout.PageStatus;
import ai.searchavo.scout.PageStatus.Reason;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.sources.research.PublicAddresses;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** UC-030 BR-014: when the Scout's own status request is made, and what it gives. */
class LivePageStatusReaderTest {

  static final Instant NOW = Instant.parse("2026-11-21T03:00:00Z");

  static final class Counts implements QuotaCounter {
    final Map<Counter, Long> counts = new HashMap<>();
    boolean down;

    @Override
    public Result take(Counter own, int ownLimit, Counter global, long globalLimit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void add(Counter counter, long amount) {
      counts.merge(counter, amount, Long::sum);
    }

    @Override
    public void giveBack(Counter counter) {}

    @Override
    public long used(Counter counter) {
      if (down) {
        throw new IllegalStateException("redis is down");
      }
      return counts.getOrDefault(counter, 0L);
    }
  }

  static final class Moving extends Clock {
    Instant now = NOW;

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  boolean circuitOpen;

  final Resilience resilience =
      new Resilience() {
        @Override
        public <T> T call(String policy, Supplier<T> work) {
          if (circuitOpen) {
            throw new CallRefused(policy, CallRefused.Reason.CIRCUIT_OPEN, null);
          }
          return work.get();
        }

        @Override
        public void require(String policy) {}
      };

  final Map<String, String> dns = new HashMap<>();
  final Map<String, LivePageStatusReader.Answer> answers = new HashMap<>();
  final Map<String, IOException> failures = new HashMap<>();
  final List<String> sent = new ArrayList<>();
  final List<InetAddress> connectedTo = new ArrayList<>();
  final List<Integer> bodyAsked = new ArrayList<>();
  final Counts counts = new Counts();
  final Moving clock = new Moving();
  LivePageStatusReader.Limits limits = new LivePageStatusReader.Limits(200, 30);

  LivePageStatusReader reader() {
    PublicAddresses addresses =
        new PublicAddresses(
            ShadowLibraries.of("z-lib"),
            host -> {
              String address = dns.get(host);
              if (address == null) {
                throw new UnknownHostException(host);
              }
              return new InetAddress[] {InetAddress.getByName(address)};
            });
    return new LivePageStatusReader(
        addresses,
        new HostsNeverRead(Map.of("coursera.org", "its terms forbid it")),
        (method, url, checked, maxBodyBytes) -> {
          sent.add(method + " " + url);
          connectedTo.addAll(checked);
          bodyAsked.add(maxBodyBytes);
          IOException failure = failures.get(method + " " + url);
          if (failure != null) {
            throw failure;
          }
          return answers.getOrDefault(
              method + " " + url, new LivePageStatusReader.Answer(404, null, ""));
        },
        resilience,
        counts,
        new QuotaKeys("", clock),
        limits,
        clock);
  }

  private void answer(String request, int status, String location, String body) {
    answers.put(request, new LivePageStatusReader.Answer(status, location, body));
  }

  private static PageStatus status(LivePageStatusReader reader, String address) {
    return reader.status(URI.create(address));
  }

  @Test
  void aRedirectIsAnsweredWithItsStatusAndLocationAfterRobotsAndWithAHeadOnly() {
    dns.put("dir.acme.dev", "93.184.216.34");
    answer("HEAD https://dir.acme.dev/go/vectorly", 302, "https://vectorly.io/", "");

    PageStatus status = status(reader(), "https://dir.acme.dev/go/vectorly");

    assertThat(status).isEqualTo(new PageStatus.Answered(302, "https://vectorly.io/"));
    assertThat(sent)
        .containsExactly(
            "GET https://dir.acme.dev/robots.txt", "HEAD https://dir.acme.dev/go/vectorly");
    assertThat(bodyAsked).containsExactly(LivePageStatusReader.MAX_ROBOTS_BYTES, 0);
  }

  @Test
  void aPageThatIsGoneIsAnsweredWithItsStatus() {
    dns.put("vectorly.io", "93.184.216.34");
    answer("HEAD https://vectorly.io/old", 410, null, "");

    assertThat(status(reader(), "https://vectorly.io/old"))
        .isEqualTo(new PageStatus.Answered(410, null));
  }

  @Test
  void aSiteThatRefusesHeadIsAskedWithGetAndNoBodyIsRead() {
    dns.put("vectorly.io", "93.184.216.34");
    answer("HEAD https://vectorly.io/go", 405, null, "");
    answer("GET https://vectorly.io/go", 301, "/home", "");

    assertThat(status(reader(), "https://vectorly.io/go"))
        .isEqualTo(new PageStatus.Answered(301, "/home"));
    assertThat(sent).endsWith("HEAD https://vectorly.io/go", "GET https://vectorly.io/go");
    assertThat(bodyAsked.subList(1, 3)).containsExactly(0, 0);
  }

  @Test
  void anAddressTheHostCheckRefusesIsNeverAsked() {
    LivePageStatusReader reader = reader();

    for (String address :
        List.of(
            "http://vectorly.io/",
            "https://10.0.0.7/",
            "https://169.254.169.254/latest/meta-data",
            "https://localhost/",
            "https://metadata.internal/",
            "https://user:secret@vectorly.io/",
            "https://vectorly.io:8443/",
            "https://xn--vectrly-9za.io/",
            "https://z-lib.org/book")) {
      assertThat(status(reader, address))
          .as(address)
          .isEqualTo(new PageStatus.Refused(Reason.HOST_CHECK));
    }
    assertThat(sent).isEmpty();
  }

  @Test
  void aHostThatIsNeverReadIsNeverAsked() {
    assertThat(status(reader(), "https://www.coursera.org/learn/ml"))
        .isEqualTo(new PageStatus.Refused(Reason.NEVER_READ));
    assertThat(sent).isEmpty();
  }

  @Test
  void aPublicNameThatResolvesToAnInternalAddressIsNotAsked() {
    dns.put("rebind.acme.dev", "169.254.169.254");
    dns.put("inside.acme.dev", "10.0.0.7");
    LivePageStatusReader reader = reader();

    for (String host : List.of("rebind", "inside", "unknown")) {
      assertThat(status(reader, "https://" + host + ".acme.dev/go"))
          .as(host)
          .isEqualTo(new PageStatus.Refused(Reason.PRIVATE_ADDRESS));
    }
    assertThat(sent).isEmpty();
  }

  @Test
  void theRequestConnectsToTheAddressesThatWereChecked() throws UnknownHostException {
    dns.put("vectorly.io", "93.184.216.34");

    status(reader(), "https://vectorly.io/go");

    assertThat(connectedTo).containsOnly(InetAddress.getByName("93.184.216.34"));
  }

  @Test
  void robotsIsHonouredForThePathAskedAndReadOnceADayPerHost() {
    dns.put("dir.acme.dev", "93.184.216.34");
    answer("GET https://dir.acme.dev/robots.txt", 200, null, "User-agent: *\nDisallow: /go/\n");
    answer("HEAD https://dir.acme.dev/tool/vectorly", 200, null, "");
    LivePageStatusReader reader = reader();

    assertThat(status(reader, "https://dir.acme.dev/go/vectorly"))
        .isEqualTo(new PageStatus.Refused(Reason.ROBOTS));
    assertThat(status(reader, "https://dir.acme.dev/tool/vectorly"))
        .isEqualTo(new PageStatus.Answered(200, null));
    assertThat(sent)
        .containsExactly(
            "GET https://dir.acme.dev/robots.txt", "HEAD https://dir.acme.dev/tool/vectorly");

    clock.now = NOW.plus(Duration.ofHours(25));
    status(reader, "https://dir.acme.dev/tool/vectorly");
    assertThat(sent).filteredOn(s -> s.endsWith("/robots.txt")).hasSize(2);
  }

  @Test
  void aSiteWithNoRobotsFileStatesNoRuleAndOneThatCannotBeReadAllowsNothing() {
    for (String host :
        List.of("none.acme.dev", "gone.acme.dev", "down.acme.dev", "wall.acme.dev")) {
      dns.put(host, "93.184.216.34");
    }
    answer("GET https://none.acme.dev/robots.txt", 404, null, "");
    answer("GET https://gone.acme.dev/robots.txt", 410, null, "");
    answer("GET https://down.acme.dev/robots.txt", 503, null, "");
    answer("GET https://wall.acme.dev/robots.txt", 403, null, "");
    failures.put("GET https://slow.acme.dev/robots.txt", new SocketTimeoutException());
    dns.put("slow.acme.dev", "93.184.216.34");
    answer("GET https://away.acme.dev/robots.txt", 301, "https://elsewhere.dev/robots.txt", "");
    dns.put("away.acme.dev", "93.184.216.34");
    LivePageStatusReader reader = reader();

    assertThat(status(reader, "https://none.acme.dev/x")).isInstanceOf(PageStatus.Answered.class);
    assertThat(status(reader, "https://gone.acme.dev/x")).isInstanceOf(PageStatus.Answered.class);
    for (String host : List.of("down", "wall", "slow", "away")) {
      assertThat(status(reader, "https://" + host + ".acme.dev/x"))
          .as(host)
          .isEqualTo(new PageStatus.Refused(Reason.ROBOTS));
    }
  }

  @Test
  void theRobotsFileOfTheSameSiteWithWwwIsRead() {
    dns.put("vectorly.io", "93.184.216.34");
    dns.put("www.vectorly.io", "93.184.216.34");
    answer("GET https://vectorly.io/robots.txt", 301, "https://www.vectorly.io/robots.txt", "");
    answer("GET https://www.vectorly.io/robots.txt", 200, null, "User-agent: *\nDisallow: /go\n");

    assertThat(status(reader(), "https://vectorly.io/go"))
        .isEqualTo(new PageStatus.Refused(Reason.ROBOTS));
  }

  @Test
  void aCapReachedOnTheRobotsFileOfTheWwwTwinIsACapAndNotARobotsRule() {
    dns.put("vectorly.io", "93.184.216.34");
    dns.put("www.vectorly.io", "93.184.216.34");
    answer("GET https://vectorly.io/robots.txt", 301, "https://www.vectorly.io/robots.txt", "");
    limits = new LivePageStatusReader.Limits(1, 30);

    assertThat(status(reader(), "https://vectorly.io/go"))
        .isEqualTo(new PageStatus.Refused(Reason.DAILY_CAP));
  }

  @Test
  void everyTryCountsOnTheDayAndOnItsHostAndPastACapNoRequestIsMade() {
    dns.put("dir.acme.dev", "93.184.216.34");
    dns.put("vectorly.io", "93.184.216.34");
    limits = new LivePageStatusReader.Limits(5, 3);
    LivePageStatusReader reader = reader();

    assertThat(status(reader, "https://dir.acme.dev/a")).isInstanceOf(PageStatus.Answered.class);
    assertThat(status(reader, "https://dir.acme.dev/b")).isInstanceOf(PageStatus.Answered.class);
    assertThat(status(reader, "https://dir.acme.dev/c"))
        .isEqualTo(new PageStatus.Refused(Reason.HOST_CAP));
    assertThat(status(reader, "https://vectorly.io/a")).isInstanceOf(PageStatus.Answered.class);
    assertThat(status(reader, "https://vectorly.io/b"))
        .isEqualTo(new PageStatus.Refused(Reason.DAILY_CAP));
    assertThat(sent).hasSize(5);
  }

  @Test
  void aRequestTheOpenCircuitRefusesSaysNothingAboutRobotsAndTheSiteIsAskedLater() {
    dns.put("vectorly.io", "93.184.216.34");
    answer("HEAD https://vectorly.io/go", 302, "https://vectorly.io/home", "");
    LivePageStatusReader reader = reader();
    circuitOpen = true;

    assertThat(status(reader, "https://vectorly.io/go"))
        .isEqualTo(new PageStatus.Refused(Reason.NOT_ASKED));
    assertThat(sent).isEmpty();

    circuitOpen = false;
    assertThat(status(reader, "https://vectorly.io/go"))
        .isEqualTo(new PageStatus.Answered(302, "https://vectorly.io/home"));
  }

  @Test
  void capsThatCannotBeReadAllowNoRequest() {
    dns.put("vectorly.io", "93.184.216.34");
    counts.down = true;

    assertThat(status(reader(), "https://vectorly.io/a"))
        .isEqualTo(new PageStatus.Refused(Reason.DAILY_CAP));
    assertThat(sent).isEmpty();
  }

  @Test
  void aSiteThatDoesNotAnswerInTimeIsATimeoutAndAnotherFailureIsUnavailable() {
    dns.put("vectorly.io", "93.184.216.34");
    answer("GET https://vectorly.io/robots.txt", 404, null, "");
    failures.put("HEAD https://vectorly.io/slow", new SocketTimeoutException());
    failures.put("HEAD https://vectorly.io/reset", new IOException("connection reset"));
    LivePageStatusReader reader = reader();

    assertThat(status(reader, "https://vectorly.io/slow"))
        .isEqualTo(new PageStatus.Refused(Reason.TIMEOUT));
    assertThat(status(reader, "https://vectorly.io/reset"))
        .isEqualTo(new PageStatus.Refused(Reason.UNAVAILABLE));
  }
}
