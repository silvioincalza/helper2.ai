package ai.helper2.sources.research;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.search.ShadowLibraries;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicAddressesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0.0.0.0",
        "10.1.2.3",
        "100.64.0.1",
        "127.0.0.1",
        "169.254.169.254",
        "172.16.0.1",
        "192.0.0.8",
        "192.168.1.1",
        "198.18.0.1",
        "198.19.255.254",
        "224.0.0.1",
        "240.0.0.1",
        "255.255.255.255",
        "::",
        "::1",
        "::127.0.0.1",
        "::ffff:10.0.0.1",
        "::ffff:169.254.169.254",
        "64:ff9b::a9fe:a9fe",
        "64:ff9b::7f00:1",
        "64:ff9b:1::a9fe:a9fe",
        "2002:a9fe:a9fe::",
        "2002:7f00:1::1",
        "2001::1",
        "2001:0:4136:e378:8000:63bf:3fff:fdd2",
        "fc00::1",
        "fd12:3456::1",
        "fe80::1",
        "ff02::1"
      })
  void aPrivateLocalOrReservedAddressIsNotPublic(String literal) throws UnknownHostException {
    assertThat(PublicAddresses.isPublic(InetAddress.getByName(literal))).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1.1.1.1",
        "8.8.8.8",
        "93.184.216.34",
        "192.0.1.1",
        "198.20.0.1",
        "2606:4700:4700::1111",
        "::ffff:8.8.8.8",
        "64:ff9b::808:808",
        "2002:808:808::1"
      })
  void aPublicAddressIsPublic(String literal) throws UnknownHostException {
    assertThat(PublicAddresses.isPublic(InetAddress.getByName(literal))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://[::127.0.0.1]/",
        "https://[64:ff9b::a9fe:a9fe]/latest/meta-data",
        "https://[2002:a9fe:a9fe::]/",
        "https://[2001::1]/",
        "https://198.18.0.1/"
      })
  void aUrlOnAnEmbeddedPrivateAddressIsRefused(String url) {
    assertThat(new PublicAddresses(ShadowLibraries.of("z-lib")).problem(url))
        .isEqualTo("a private or local address");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ftp://acme.dev/", "https:///path", "https://acme.dev:8443/"})
  void aUrlThatIsNotHttpOnAStandardPortIsRefusedBeforeAnyLookup(String url) {
    PublicAddresses addresses =
        new PublicAddresses(
            ShadowLibraries.of("z-lib"),
            host -> {
              throw new AssertionError("no lookup");
            });

    assertThat(addresses.problem(url)).isNotNull();
  }
}
