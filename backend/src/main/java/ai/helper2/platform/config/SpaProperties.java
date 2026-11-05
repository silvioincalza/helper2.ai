package ai.helper2.platform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Where the built SPA is served from, from {@code helper2.spa}. */
@Validated
@ConfigurationProperties("helper2.spa")
public record SpaProperties(@NotBlank String location) {}
