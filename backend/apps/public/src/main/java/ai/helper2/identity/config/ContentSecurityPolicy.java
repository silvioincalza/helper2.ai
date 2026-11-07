package ai.helper2.identity.config;

import ai.helper2.monetisation.Monetisation;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The public site's Content Security Policy (ADR 0025, 6; ADR 0030). It starts from the strict
 * policy and adds the origins of a monetisation part only while that part is on. The back office
 * sends its own fixed policy, without them (ADR 0032).
 */
final class ContentSecurityPolicy {

  /** Google Analytics 4 without Google signals or ads features (Google tag CSP guide). */
  static final String GA4_SCRIPTS = "https://www.googletagmanager.com";

  static final String GA4_BEACONS =
      "https://*.google-analytics.com https://*.analytics.google.com https://www.googletagmanager.com";

  /** Google's guide also lists *.google.com for GA4 requests. */
  static final String GA4_REQUESTS = GA4_BEACONS + " https://*.google.com";

  /** The EthicalAds client, its ad decisions and its images. */
  static final String ADS_SCRIPTS = "https://media.ethicalads.io https://server.ethicalads.io";

  static final String ADS_REQUESTS = "https://server.ethicalads.io";

  static final String ADS_IMAGES = "https://media.ethicalads.io https://server.ethicalads.io";

  private ContentSecurityPolicy() {}

  /** Where the request's nonce goes in a policy ({@link CspHeaderWriter} fills it in). */
  static final String NONCE = "{nonce}";

  /**
   * Google AdSense supports only a strict nonce policy, not an allowlist (ADR 0030): scripts run by
   * nonce, and what they load runs too ('strict-dynamic'). 'unsafe-inline', https: and http: only
   * for browsers that know no nonce; they ignore the rest. Frames, images and requests go to any
   * https origin, because the ad's origins change.
   */
  static final String ADSENSE =
      "default-src 'self'; script-src 'nonce-"
          + NONCE
          + "' 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:; "
          + "style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self' https:; "
          + "frame-src https:; font-src 'self' https:; object-src 'none'; base-uri 'none'; "
          + "form-action 'self'; frame-ancestors 'none'";

  static String forPublicSite(Monetisation monetisation) {
    if (monetisation.adsenseOn()) {
      return ADSENSE;
    }
    Map<String, String> d = strict();
    if (monetisation.analyticsOn()) {
      add(d, "script-src", GA4_SCRIPTS);
      add(d, "connect-src", GA4_REQUESTS);
      add(d, "img-src", GA4_BEACONS);
    }
    if (monetisation.adsOn()) {
      add(d, "script-src", ADS_SCRIPTS);
      add(d, "connect-src", ADS_REQUESTS);
      add(d, "img-src", ADS_IMAGES);
      // The EthicalAds client writes its own <style>; inline styles only, scripts stay strict.
      add(d, "style-src", "'unsafe-inline'");
    }
    return write(d);
  }

  private static Map<String, String> strict() {
    Map<String, String> d = new LinkedHashMap<>();
    for (String directive : PublicSecurityConfiguration.CSP.split(";")) {
      String[] parts = directive.strip().split(" ", 2);
      d.put(parts[0], parts.length > 1 ? parts[1] : "");
    }
    return d;
  }

  private static void add(Map<String, String> d, String directive, String sources) {
    d.merge(directive, sources, (a, b) -> a + " " + b);
  }

  private static String write(Map<String, String> d) {
    StringBuilder out = new StringBuilder();
    d.forEach((k, v) -> {
      if (!out.isEmpty()) {
        out.append("; ");
      }
      out.append(v.isEmpty() ? k : k + " " + v);
    });
    return out.toString();
  }
}
