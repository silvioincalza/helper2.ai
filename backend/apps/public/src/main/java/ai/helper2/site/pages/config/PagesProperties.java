package ai.helper2.site.pages.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.pages")
public record PagesProperties(
    @NotBlank @Pattern(regexp = "https?://[^/]+") String publicBaseUrl,
    boolean noindex,
    @NotBlank String template,
    @Min(1) @jakarta.validation.constraints.Max(50_000) int sitemapLimit) {}
