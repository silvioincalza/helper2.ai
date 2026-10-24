package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

/** Same simple names and hierarchy as the OpenAI Java SDK errors; ModelErrors matches by name. */
class ModelErrorsTest {

    static class OpenAIException extends RuntimeException {
    }

    static class OpenAIServiceException extends OpenAIException {
    }

    static final class RateLimitException extends OpenAIServiceException {
    }

    static final class BadRequestException extends OpenAIServiceException {
    }

    @Test
    void rateLimitsAndIoAreTransientProviderErrors() {
        assertThat(ModelErrors.isTransient(new RateLimitException())).isTrue();
        assertThat(ModelErrors.isTransient(new UncheckedIOException(new IOException("reset")))).isTrue();
        assertThat(ModelErrors.isProviderError(new RateLimitException())).isTrue();
    }

    @Test
    void aRejectedCallIsAProviderErrorButNotTransient() {
        RuntimeException wrapped = new IllegalStateException("call failed", new BadRequestException());

        assertThat(ModelErrors.isTransient(wrapped)).isFalse();
        assertThat(ModelErrors.isProviderError(wrapped)).isTrue();
    }

    @Test
    void anUnusableAnswerIsNotAProviderError() {
        RuntimeException parse = new IllegalStateException("cannot convert", new IllegalArgumentException("bad JSON"));

        assertThat(ModelErrors.isProviderError(parse)).isFalse();
    }

    @Test
    void eachFailureHasACategoryForLogsAndMetrics() {
        assertThat(ModelErrors.category(new UncheckedIOException(new InterruptedIOException("timeout")))).isEqualTo("timeout");
        assertThat(ModelErrors.category(new IllegalStateException(new SocketTimeoutException("Read timed out")))).isEqualTo("timeout");
        assertThat(ModelErrors.category(new RateLimitException())).isEqualTo("rate_limited");
        assertThat(ModelErrors.category(new BadRequestException())).isEqualTo("unavailable");
        assertThat(ModelErrors.category(new IllegalStateException("x", new tools.jackson.core.exc.StreamReadException(null, "bad"))))
            .isEqualTo("parse_error");
        assertThat(ModelErrors.category(new IllegalStateException("other"))).isEqualTo("error");
    }
}
