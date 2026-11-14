package ai.helper2.site.csp;

import ai.helper2.site.monetisation.Monetisation;
import java.util.LinkedHashMap;
import java.util.Map;

final class ContentSecurityPolicy {
  static final String STRICT =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
          + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

  static final String GA4_SCRIPTS = "https://www.googletagmanager.com";

  static final String GA4_BEACONS =
      "https://*.google-analytics.com https://*.analytics.google.com https://www.googletagmanager.com";

  static final String GA4_REQUESTS = GA4_BEACONS + " https://*.google.com";

  static final String ADS_SCRIPTS = "https://media.ethicalads.io https://server.ethicalads.io";

  static final String ADS_REQUESTS = "https://server.ethicalads.io";

  static final String ADS_IMAGES = "https://media.ethicalads.io https://server.ethicalads.io";

  private ContentSecurityPolicy() {}

  static final String NONCE = "{nonce}";

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
      add(d, "style-src", "'unsafe-inline'");
    }
    return write(d);
  }

  private static Map<String, String> strict() {
    Map<String, String> d = new LinkedHashMap<>();
    for (String directive : STRICT.split(";")) {
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
