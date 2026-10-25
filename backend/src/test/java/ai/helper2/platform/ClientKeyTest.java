package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientKeyTest {

    @Test
    void anIpv4AddressIsItsOwnClient() {
        assertThat(ClientKey.of("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientKey.of("203.0.113.8")).isNotEqualTo(ClientKey.of("203.0.113.7"));
    }

    @Test
    void ipv6AddressesInOneSlash64AreOneClient() {
        assertThat(ClientKey.of("2001:db8:0:7::1")).isEqualTo(ClientKey.of("2001:db8:0:7:ffff:1:2:3"))
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
        assertThat(ClientKey.of("[2001:0db8:0001:0002:aaaa::1]")).isEqualTo(ClientKey.of("2001:db8:1:2::9"));
        assertThat(ClientKey.of("fe80::1%25eth0")).isEqualTo(ClientKey.of("fe80::2"));
    }

    /** The rate limiters and the guide quota key a client the same way, zone id included. */
    @Test
    void theRateLimitersCountAClientByItsKey() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(1, java.time.Duration.ofMinutes(1), java.time.Clock.systemUTC());
        assertThat(limiter.acquire("2001:db8:0:7::1%eth0")).isZero();
        assertThat(limiter.acquire("2001:db8:0:7::2")).as("same /64").isPositive();
        assertThat(RedisRateLimiter.key("guide", "[2001:db8:0:7::1%eth0]")).isEqualTo("h2:v1:rate:guide:20010db800000007::/64");
        assertThat(limiter.acquire(null)).isZero();
    }

    @Test
    void somethingThatIsNotAnAddressIsKeptAsItIs() {
        assertThat(ClientKey.of(null)).isEqualTo("unknown");
        assertThat(ClientKey.of("not:an:address")).isEqualTo("not:an:address");
    }
}
