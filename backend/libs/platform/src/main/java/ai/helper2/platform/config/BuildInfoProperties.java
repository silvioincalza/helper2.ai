package ai.helper2.platform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The running build, from {@code helper2.build} (the chart passes the image tag). Named apart from
 * Spring Boot's own {@code BuildProperties}.
 */
@Validated
@ConfigurationProperties("helper2.build")
public record BuildInfoProperties(@NotBlank String version) {}
