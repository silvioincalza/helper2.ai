package ai.helper2.identity.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Sign-in, ADMIN and the security headers, from {@code helper2.identity}. The defaults and the
 * {@code H2_} variables are in application.yaml (ADR 0007 point 8).
 *
 * @param frontendUrl where a sign-in on the public site returns to (the web app)
 * @param admins "registrationId:subject" (or "registrationId:email") entries granted ADMIN on the
 *     back office; trimmed, blank entries dropped
 * @param metricsPublic {@code /actuator/prometheus} without sign-in
 * @param noindex {@code X-Robots-Tag: noindex} on every response
 */
@Validated
@ConfigurationProperties("helper2.identity")
public record IdentityProperties(
    @NotBlank String frontendUrl,
    @NotNull List<String> admins,
    boolean metricsPublic,
    boolean noindex) {

  public IdentityProperties {
    admins =
        admins == null
            ? List.of()
            : admins.stream().map(String::strip).filter(a -> !a.isEmpty()).toList();
  }
}
