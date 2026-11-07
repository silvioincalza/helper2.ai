package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Who a rate limit counts for (ADR 0035): on the admin host, never an address the client sends. */
class RateLimitKeyTest {

  private static MockHttpServletRequest from(String address, String forwardedFor) {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/search");
    req.setRemoteAddr(address);
    req.addHeader("X-Forwarded-For", forwardedFor);
    return req;
  }

  @Test
  void onTheAdminHostEveryoneNotSignedInSharesOneKeyWhateverAddressTheySend() {
    String one = RateLimitInterceptor.clientKey(from("10.0.0.1", "203.0.113.10"), true);
    String two = RateLimitInterceptor.clientKey(from("10.0.0.2", "203.0.113.11"), true);
    assertThat(one).isEqualTo(two).isEqualTo(RateLimitInterceptor.ADMIN_HOST_ANONYMOUS);
  }

  @Test
  void onTheAdminHostASignedInAdminCountsAsThemself() {
    MockHttpServletRequest req = from("10.0.0.1", "203.0.113.10");
    req.setUserPrincipal(() -> "12345");
    assertThat(RateLimitInterceptor.clientKey(req, true))
        .isEqualTo(RateLimitInterceptor.ADMIN_HOST_USER + "12345");
  }

  @Test
  void onThePublicHostTheKeyIsTheClientAddress() {
    assertThat(RateLimitInterceptor.clientKey(from("198.51.100.7", "203.0.113.10"), false))
        .isEqualTo("198.51.100.7");
  }
}
