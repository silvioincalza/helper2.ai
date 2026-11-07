package ai.helper2.platform.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The chat model every module may call, from {@code helper2.llm}. {@code chat-provider} and {@code
 * chat-model} repeat Spring AI's own keys in application.yaml, so the code reads them here. Empty
 * model names mean "not set": the profile {@code llm-nebius} sets them.
 */
@Validated
@ConfigurationProperties("helper2.llm")
public record LlmProperties(
    @NotBlank String chatProvider,
    @NotNull String chatModel,
    @NotNull String providerLabel,
    @NotNull String fastModel,
    @NotNull String reasoningModel,
    boolean nativeSchema) {}
