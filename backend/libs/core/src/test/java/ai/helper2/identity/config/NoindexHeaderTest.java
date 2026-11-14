package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class NoindexHeaderTest {

  @Test
  void writesNoindexOnEveryResponse() {
    MockHttpServletResponse res = new MockHttpServletResponse();
    WebSecurity.NOINDEX.writeHeaders(new MockHttpServletRequest(), res);
    assertThat(res.getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow");
  }
}
