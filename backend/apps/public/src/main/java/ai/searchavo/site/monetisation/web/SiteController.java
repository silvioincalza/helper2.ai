package ai.searchavo.site.monetisation.web;

import ai.searchavo.api.PublicCache;
import ai.searchavo.api.generated.SiteApi;
import ai.searchavo.api.generated.model.MonetisationDto;
import ai.searchavo.api.generated.model.SiteDto;
import ai.searchavo.site.monetisation.CurrentMonetisation;
import ai.searchavo.site.monetisation.Monetisation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SiteController implements SiteApi {

  private static final CacheControl CACHE = PublicCache.SHORT;

  private final CurrentMonetisation monetisation;

  SiteController(CurrentMonetisation monetisation) {
    this.monetisation = monetisation;
  }

  @Override
  public ResponseEntity<SiteDto> getSite() {
    return ResponseEntity.ok().cacheControl(CACHE).body(new SiteDto(dto(monetisation.now())));
  }

  static MonetisationDto dto(Monetisation shown) {
    return new MonetisationDto(
        shown.githubSponsors().orElse(null),
        shown.stripePayment().orElse(null),
        shown.ga4MeasurementId().orElse(null),
        shown.adsenseOn() ? shown.adsenseClient().orElse(null) : null,
        shown.adsenseOn() ? shown.adsenseSlot().orElse(null) : null,
        shown.adsenseOn() ? shown.adsenseSlotSearch().orElse(null) : null,
        shown.adsenseOn() ? shown.adsenseSlotGuide().orElse(null) : null);
  }
}
