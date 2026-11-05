package ai.helper2.admin.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The back office, from {@code helper2.admin}.
 *
 * @param grafanaUrl the metrics dashboard the overview links to; empty = no link. The overview
 *     checks it is an absolute http(s) URL.
 */
@Validated
@ConfigurationProperties("helper2.admin")
public record AdminProperties(@NotNull String grafanaUrl) {}
