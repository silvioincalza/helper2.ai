package ai.helper2.platform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the back office serves the admin web app from, from {@code helper2.spa}. The public app
 * serves no web files: the {@code web} image does (ADR 0069).
 */
@Validated
@ConfigurationProperties("helper2.spa")
public record SpaProperties(@NotBlank String location) {}
