package ai.helper2.platform.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.llm")
public record LlmProperties(
    @NotBlank String chatProvider,
    @NotNull String chatModel,
    @NotNull String providerLabel,
    @NotNull String fastModel,
    @NotNull String reasoningModel,
    boolean nativeSchema) {}
