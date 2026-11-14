package ai.helper2.site.monetisation.web;

import ai.helper2.api.PublicCache;
import ai.helper2.api.generated.SiteApi;
import ai.helper2.api.generated.model.MonetisationDto;
import ai.helper2.api.generated.model.SiteDto;
import ai.helper2.site.monetisation.Monetisation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SiteController implements SiteApi {

  private static final CacheControl CACHE = PublicCache.SHORT;

  private final SiteDto site;

  SiteController(Monetisation monetisation) {
    this.site = new SiteDto(dto(monetisation));
  }

  @Override
  public ResponseEntity<SiteDto> getSite() {
    return ResponseEntity.ok().cacheControl(CACHE).body(site);
  }

  static MonetisationDto dto(Monetisation shown) {
    return new MonetisationDto(
        shown.githubSponsors().orElse(null),
        shown.stripePayment().orElse(null),
        shown.ga4MeasurementId().orElse(null),
        shown.ethicalAdsPublisher().orElse(null),
        shown.adsenseOn() ? shown.adsenseClient().orElse(null) : null,
        shown.adsenseOn() ? shown.adsenseSlot().orElse(null) : null);
  }
}
