package ai.searchavo.site.pages.config;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.pages.indexnow")
public record IndexNowKeyProperties(@Pattern(regexp = "|[A-Za-z0-9-]{8,128}") String key) {}
