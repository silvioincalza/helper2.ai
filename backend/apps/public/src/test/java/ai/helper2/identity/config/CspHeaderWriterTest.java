package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.CspNonce;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CspHeaderWriterTest {

  private static final String PUBLIC = "default-src 'self'; script-src 'self' https://ads.example";

  @Test
  void everyRequestGetsThePublicPolicy() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse res = new MockHttpServletResponse();
    new CspHeaderWriter(PUBLIC).writeHeaders(req, res);
    assertThat(res.getHeader(CspHeaderWriter.HEADER)).isEqualTo(PUBLIC);
  }

  @Test
  void anExistingPolicyIsKept() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse res = new MockHttpServletResponse();
    res.setHeader(CspHeaderWriter.HEADER, "set-before");
    new CspHeaderWriter(PUBLIC).writeHeaders(req, res);
    assertThat(res.getHeader(CspHeaderWriter.HEADER)).isEqualTo("set-before");
  }

  @Test
  void theNoncePolicyNamesThisRequestsNonce() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse res = new MockHttpServletResponse();
    new CspHeaderWriter(ContentSecurityPolicy.ADSENSE).writeHeaders(req, res);

    String nonce = CspNonce.of(req);
    assertThat(nonce).hasSizeGreaterThanOrEqualTo(24);
    assertThat(res.getHeader(CspHeaderWriter.HEADER)).contains("'nonce-" + nonce + "'");
    assertThat(CspNonce.of(new MockHttpServletRequest())).isNotEqualTo(nonce);
  }
}
