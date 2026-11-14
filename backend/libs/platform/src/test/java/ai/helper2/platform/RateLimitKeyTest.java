package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimitKeyTest {

  private static MockHttpServletRequest from(String address, String forwardedFor) {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/search");
    req.setRemoteAddr(address);
    req.addHeader("X-Forwarded-For", forwardedFor);
    return req;
  }

  @Test
  void onTheAdminHostEveryoneNotSignedInSharesOneKeyWhateverAddressTheySend() {
    String one = RateLimitKey.SIGNED_IN_ADMIN.of(from("10.0.0.1", "203.0.113.10"));
    String two = RateLimitKey.SIGNED_IN_ADMIN.of(from("10.0.0.2", "203.0.113.11"));
    assertThat(one).isEqualTo(two).isEqualTo(RateLimitKey.ADMIN_HOST_ANONYMOUS);
  }

  @Test
  void onTheAdminHostASignedInAdminCountsAsThemself() {
    MockHttpServletRequest req = from("10.0.0.1", "203.0.113.10");
    req.setUserPrincipal(() -> "12345");
    assertThat(RateLimitKey.SIGNED_IN_ADMIN.of(req))
        .isEqualTo(RateLimitKey.ADMIN_HOST_USER + "12345");
  }

  @Test
  void onThePublicHostTheKeyIsTheClientAddress() {
    assertThat(RateLimitKey.CLIENT_ADDRESS.of(from("198.51.100.7", "203.0.113.10")))
        .isEqualTo("198.51.100.7");
  }
}
