package ai.helper2.identity.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Sign-in, ADMIN and the security headers, from {@code helper2.identity}. The defaults and the
 * {@code H2_} variables are in application.yaml (ADR 0007 point 8).
 *
 * @param frontendUrl where sign-in returns to (the SPA)
 * @param adminPort the back office's own port (ADR 0035); 0 = one port for everything
 * @param admins "registrationId:subject" (or "registrationId:email") entries granted ADMIN;
 *     trimmed, blank entries dropped
 * @param metricsPublic {@code /actuator/prometheus} without sign-in
 * @param noindex {@code X-Robots-Tag: noindex} on every response
 */
@Validated
@ConfigurationProperties("helper2.identity")
record IdentityProperties(
    @NotBlank String frontendUrl,
    @PositiveOrZero @Max(65535) int adminPort,
    @NotNull List<String> admins,
    boolean metricsPublic,
    boolean noindex) {

  IdentityProperties {
    admins =
        admins == null
            ? null
            : admins.stream().map(String::strip).filter(a -> !a.isEmpty()).toList();
  }
}
