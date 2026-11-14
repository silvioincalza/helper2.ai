package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.quota.QuotaKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ClientKeyTest {

  @Test
  void anIpv4AddressIsItsOwnClient() {
    assertThat(ClientKey.of("203.0.113.7")).isEqualTo("203.0.113.7");
    assertThat(ClientKey.of("203.0.113.8")).isNotEqualTo(ClientKey.of("203.0.113.7"));
  }

  @Test
  void ipv6AddressesInOneSlash64AreOneClient() {
    assertThat(ClientKey.of("2001:db8:0:7::1"))
        .isEqualTo(ClientKey.of("2001:db8:0:7:ffff:1:2:3"))
        .isEqualTo("20010db800000007::/64");
    assertThat(ClientKey.of("[2001:db8:0:7::1%eth0]")).isEqualTo(ClientKey.of("2001:db8:0:7::9"));
    assertThat(ClientKey.of("2001:db8:0:8::1")).isNotEqualTo(ClientKey.of("2001:db8:0:7::1"));
  }

  @Test
  void anIpv4MappedAddressIsTheIpv4Client() {
    assertThat(ClientKey.of("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
  }

  @Test
  void bracketsLeadingZerosAndZoneIdsDoNotMakeANewClient() {
    assertThat(ClientKey.of("[2001:0db8:0001:0002:aaaa::1]"))
        .isEqualTo(ClientKey.of("2001:db8:1:2::9"));
    assertThat(ClientKey.of("fe80::1%25eth0")).isEqualTo(ClientKey.of("fe80::2"));
  }

  @Test
  void theRateLimitersCountAClientByItsKey() {
    InMemoryRateLimiter limiter =
        new InMemoryRateLimiter(1, java.time.Duration.ofMinutes(1), java.time.Clock.systemUTC());
    assertThat(limiter.acquire("2001:db8:0:7::1%eth0")).isZero();
    assertThat(limiter.acquire("2001:db8:0:7::2")).as("same /64").isPositive();
    QuotaKeys keys = new QuotaKeys("test-secret", java.time.Clock.systemUTC());
    RedisRateLimiter redis =
        new RedisRateLimiter(
            "guide",
            1,
            java.time.Duration.ofMinutes(1),
            keys,
            null,
            null,
            limiter,
            new SimpleMeterRegistry());
    assertThat(redis.key("[2001:db8:0:7::1%eth0]"))
        .isEqualTo("h2:v1:rate:guide:" + keys.client("20010db800000007::/64"))
        .isEqualTo(redis.key("2001:db8:0:7::9"));
    assertThat(limiter.acquire(null)).isZero();
  }

  @Test
  void aClientsNetworkIsItsIpv4Slash24OrItsIpv6Slash56() {
    assertThat(ClientKey.network(ClientKey.of("203.0.113.7"))).isEqualTo("203.0.113.0/24");
    assertThat(ClientKey.network(ClientKey.of("2001:db8:0:7::1")))
        .isEqualTo("20010db800000000::/56")
        .isEqualTo(ClientKey.network(ClientKey.of("2001:db8:0:ff::9")));
    assertThat(ClientKey.network("unknown")).isEqualTo("unknown");
  }

  @Test
  void theFirstAndLastSlash64OfASlash56ShareItsNetwork() {
    assertThat(ClientKey.network(ClientKey.of("2001:db8:aa:bb00::1")))
        .isEqualTo(ClientKey.network(ClientKey.of("2001:db8:aa:bbff:ffff::1")))
        .isEqualTo("20010db800aabb00::/56");
  }

  @Test
  void theNextSlash64AfterASlash56IsAnotherNetwork() {
    assertThat(ClientKey.network(ClientKey.of("2001:db8:aa:bbff::1")))
        .isNotEqualTo(ClientKey.network(ClientKey.of("2001:db8:aa:bc00::1")))
        .isNotEqualTo(ClientKey.network(ClientKey.of("2001:db8:aa:ba00::1")));
    assertThat(ClientKey.network(ClientKey.of("2001:db8:aa:bc00::1")))
        .isEqualTo("20010db800aabc00::/56");
  }

  @Test
  void aSlash56NetworkIsNotWiderThanItsSlash48() {
    assertThat(ClientKey.network(ClientKey.of("2001:db8:aa:100::1")))
        .isNotEqualTo(ClientKey.network(ClientKey.of("2001:db8:aa:200::1")));
  }

  @Test
  void somethingThatIsNotAnAddressIsKeptAsItIs() {
    assertThat(ClientKey.of(null)).isEqualTo("unknown");
    assertThat(ClientKey.of("not:an:address")).isEqualTo("not:an:address");
  }
}
