package ai.helper2.pages.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Pages for crawlers (ADR 0050), bound from {@code helper2.pages} (defaults and H2_ names in
 * application.yaml).
 *
 * @param publicBaseUrl the public host every canonical link and sitemap address uses, without a
 *     trailing slash; the same on staging, so staging never names itself
 * @param noindex true on staging: robots.txt allows all (the X-Robots-Tag header keeps it out of
 *     the index) and there is no sitemap
 * @param template the built app shell the pages are written into
 * @param sitemapLimit resource pages in one sitemap; Google reads at most 50,000 per file
 */
@Validated
@ConfigurationProperties("helper2.pages")
public record PagesProperties(
    @NotBlank @Pattern(regexp = "https?://[^/]+") String publicBaseUrl,
    boolean noindex,
    @NotBlank String template,
    @Min(1) @jakarta.validation.constraints.Max(50_000) int sitemapLimit) {}
