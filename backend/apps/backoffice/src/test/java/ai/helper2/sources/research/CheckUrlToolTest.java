package ai.helper2.sources.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.research.ResearchTool;
import ai.helper2.search.ShadowLibraries;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CheckUrlToolTest {

  final Deque<InetAddress> answers = new ArrayDeque<>();
  final List<String> lookups = new ArrayList<>();
  final Deque<CheckUrlTool.Hop> hops = new ArrayDeque<>();
  final List<List<InetAddress>> connectedTo = new ArrayList<>();

  final List<Duration> timeLeft = new ArrayList<>();
  final AtomicLong now = new AtomicLong();
  InetAddress[] nextAnswer;

  CheckUrlTool tool() {
    PublicAddresses addresses =
        new PublicAddresses(
            ShadowLibraries.of("z-lib"),
            host -> {
              lookups.add(host);
              if (nextAnswer != null) {
                return nextAnswer;
              }
              return new InetAddress[] {answers.removeFirst()};
            });
    return new CheckUrlTool(
        addresses,
        (url, checked, left) -> {
          connectedTo.add(checked);
          timeLeft.add(left);
          now.addAndGet(Duration.ofSeconds(8).toNanos());
          return hops.removeFirst();
        },
        Duration.ofSeconds(20),
        now::get);
  }

  static InetAddress ip(String literal) throws UnknownHostException {
    return InetAddress.getByName(literal);
  }

  static String url(String url) {
    return "{\"url\":\"" + url + "\"}";
  }

  @Test
  void theHostIsLookedUpOnceAndTheRequestGoesToTheCheckedAddress() throws Exception {
    answers.add(ip("93.184.216.34"));
    answers.add(ip("127.0.0.1"));
    hops.add(new CheckUrlTool.Hop(200, null));

    ResearchTool.Output out = tool().call(url("https://acme.dev/"));

    assertThat(out.text()).contains("200 https://acme.dev/", "final: https://acme.dev/");
    assertThat(lookups).containsExactly("acme.dev");
    assertThat(connectedTo).containsExactly(List.of(ip("93.184.216.34")));
  }

  @Test
  void aRedirectToAHostThatResolvesPrivateIsNotFollowed() throws Exception {
    answers.add(ip("93.184.216.34"));
    answers.add(ip("169.254.169.254"));
    hops.add(new CheckUrlTool.Hop(302, "https://metadata.acme.dev/"));

    ResearchTool.Output out = tool().call(url("https://acme.dev/"));

    assertThat(out.text())
        .contains("302 https://acme.dev/")
        .contains("not checked: https://metadata.acme.dev/: a private or local address");
    assertThat(connectedTo).hasSize(1);
  }

  @Test
  void anHttpUrlIsRefusedWithTheReasonAndNoLookup() {
    ResearchTool.Output out = tool().call(url("http://acme.dev/"));

    assertThat(out.text()).contains(CheckUrlTool.HTTPS_ONLY);
    assertThat(lookups).isEmpty();
    assertThat(connectedTo).isEmpty();
  }

  @Test
  void aRedirectToHttpIsNotFollowed() throws Exception {
    answers.add(ip("93.184.216.34"));
    hops.add(new CheckUrlTool.Hop(301, "http://acme.dev/"));

    ResearchTool.Output out = tool().call(url("https://acme.dev/"));

    assertThat(out.text())
        .contains("301 https://acme.dev/")
        .contains("not checked: http://acme.dev/: " + CheckUrlTool.HTTPS_ONLY);
  }

  @Test
  void anEmbeddedPrivateAddressIsRefusedBeforeAnyRequest() throws Exception {
    answers.add(ip("64:ff9b::a9fe:a9fe"));

    ResearchTool.Output out = tool().call(url("https://acme.dev/"));

    assertThat(out.text()).contains("a private or local address");
    assertThat(connectedTo).isEmpty();
  }

  @Test
  void aRelativeRedirectIsResolvedAgainstTheHopAndLookedUpAgain() throws Exception {
    answers.add(ip("93.184.216.34"));
    answers.add(ip("93.184.216.34"));
    hops.add(new CheckUrlTool.Hop(301, "/docs"));
    hops.add(new CheckUrlTool.Hop(200, null));

    ResearchTool.Output out = tool().call(url("https://acme.dev/"));

    assertThat(out.text()).contains("final: https://acme.dev/docs");
    assertThat(lookups).containsExactly("acme.dev", "acme.dev");
  }

  @Test
  void atMostTwoAddressesOfAHopAreTried() throws Exception {
    nextAnswer = new InetAddress[] {ip("93.184.216.34"), ip("93.184.216.35"), ip("93.184.216.36")};
    hops.add(new CheckUrlTool.Hop(200, null));

    tool().call(url("https://acme.dev/"));

    assertThat(connectedTo).containsExactly(List.of(ip("93.184.216.34"), ip("93.184.216.35")));
  }

  @Test
  void theWholeCallHasOneDeadlineAcrossItsRedirects() throws Exception {
    nextAnswer = new InetAddress[] {ip("93.184.216.34")};
    hops.add(new CheckUrlTool.Hop(301, "/a"));
    hops.add(new CheckUrlTool.Hop(301, "/b"));
    hops.add(new CheckUrlTool.Hop(301, "/c"));

    ResearchTool.Output out = tool().call(url("https://acme.dev/"));

    assertThat(out.text()).contains("stopped after 20 seconds");
    assertThat(timeLeft)
        .containsExactly(Duration.ofSeconds(20), Duration.ofSeconds(12), Duration.ofSeconds(4));
  }

  @Test
  void aServerThatSendsBytesSlowlyIsCancelledAtTheDeadline() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread.ofVirtual().start(() -> dribble(server));
      long started = System.nanoTime();

      assertThatThrownBy(() ->
              CheckUrlTool.pinnedHead(
                  java.net.URI.create("http://slow.test:" + server.getLocalPort() + "/"),
                  List.of(InetAddress.getLoopbackAddress()),
                  Duration.ofMillis(500)))
          .isInstanceOf(IOException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }
  }

  private static void dribble(ServerSocket server) {
    try (Socket client = server.accept()) {
      OutputStream out = client.getOutputStream();
      out.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII));
      for (int i = 0; i < 100; i++) {
        out.write('X');
        out.flush();
        Thread.sleep(100);
      }
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
