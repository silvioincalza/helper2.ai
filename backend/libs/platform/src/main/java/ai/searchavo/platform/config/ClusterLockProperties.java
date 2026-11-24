package ai.searchavo.platform.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.cluster-lock")
public record ClusterLockProperties(@NotNull @Pattern(regexp = "[a-z_]{1,63}") String table) {}
