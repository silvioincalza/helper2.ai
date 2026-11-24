package ai.searchavo.site.pages.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.pages")
public record PagesProperties(
    @NotBlank @Pattern(regexp = "https?://[^/]+") String publicBaseUrl,
    boolean noindex,
    @NotBlank String template,
    @Min(1) @jakarta.validation.constraints.Max(1_000_000) int sitemapLimit) {}
