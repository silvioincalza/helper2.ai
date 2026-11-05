package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CspHeaderWriterTest {

  private static final String PUBLIC = "default-src 'self'; script-src 'self' https://ads.example";

  private static String cspOn(int adminPort, int localPort) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setLocalPort(localPort);
    MockHttpServletResponse res = new MockHttpServletResponse();
    new CspHeaderWriter(new AdminPort(adminPort), PUBLIC).writeHeaders(req, res);
    return res.getHeader(CspHeaderWriter.HEADER);
  }

  @Test
  void theAdminPortAlwaysGetsTheStrictPolicy() {
    assertThat(cspOn(8082, 8082)).isEqualTo(SecurityConfiguration.CSP);
  }

  @Test
  void thePublicPortGetsThePublicPolicy() {
    assertThat(cspOn(8082, 8080)).isEqualTo(PUBLIC);
  }

  @Test
  void withoutAnAdminPortEveryRequestIsPublic() {
    assertThat(cspOn(0, 8080)).isEqualTo(PUBLIC);
  }

  @Test
  void anExistingPolicyIsKept() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse res = new MockHttpServletResponse();
    res.setHeader(CspHeaderWriter.HEADER, "set-before");
    new CspHeaderWriter(new AdminPort(0), PUBLIC).writeHeaders(req, res);
    assertThat(res.getHeader(CspHeaderWriter.HEADER)).isEqualTo("set-before");
  }
}
