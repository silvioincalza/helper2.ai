package ai.helper2.sources.research;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.research.ResearchTool;
import ai.helper2.search.ShadowLibraries;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckUrlToolTest {

  final Deque<InetAddress> answers = new ArrayDeque<>();
  final List<String> lookups = new ArrayList<>();
  final Deque<CheckUrlTool.Hop> hops = new ArrayDeque<>();
  final List<List<InetAddress>> connectedTo = new ArrayList<>();

  CheckUrlTool tool() {
    PublicAddresses addresses =
        new PublicAddresses(
            ShadowLibraries.of("z-lib"),
            host -> {
              lookups.add(host);
              return new InetAddress[] {answers.removeFirst()};
            });
    return new CheckUrlTool(
        addresses,
        (url, checked) -> {
          connectedTo.add(checked);
          return hops.removeFirst();
        });
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
}
