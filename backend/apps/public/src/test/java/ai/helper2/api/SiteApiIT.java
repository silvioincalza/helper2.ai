package ai.helper2.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SiteApiIT extends ApiTestBase {

  private static MockHttpServletRequestBuilder site(String address) {
    return get("/api/v1/site")
        .with(r -> {
          r.setRemoteAddr(address);
          return r;
        });
  }

  @Test
  void aGuestReadsTheSitesSettingsWithNoCookieAndMayCacheThem() throws Exception {
    String body =
        mvc.perform(site("10.0.52.1"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=300, public"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.monetisation.githubSponsorsUrl").value((Object) null))
            .andExpect(jsonPath("$.monetisation.stripePaymentUrl").value((Object) null))
            .andExpect(jsonPath("$.monetisation.ga4MeasurementId").value((Object) null))
            .andExpect(jsonPath("$.monetisation.ethicalAdsPublisher").value((Object) null))
            .andExpect(jsonPath("$.monetisation.adsenseClient").value((Object) null))
            .andExpect(jsonPath("$.monetisation.adsenseSlot").value((Object) null))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("Site", body);
  }

  @Test
  void theSessionInfoNoLongerCarriesTheSitesSettings() throws Exception {
    mvc.perform(get("/api/v1/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.monetisation").doesNotExist());
  }

  @Test
  void itIsRateLimitedLikeTheOtherPublicReads() throws Exception {
    for (int i = 0; i < 60; i++) {
      mvc.perform(site("10.0.52.2")).andExpect(status().isOk());
    }
    mvc.perform(site("10.0.52.3")).andExpect(status().isOk());
    mvc.perform(site("10.0.52.2"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
  }
}
