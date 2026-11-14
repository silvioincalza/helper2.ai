package ai.helper2.admin.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.admin")
public record AdminProperties(
    @NotNull String grafanaUrl,
    @NotBlank String publicBaseUrl,
    @PositiveOrZero int guideDailyModelCalls) {}
