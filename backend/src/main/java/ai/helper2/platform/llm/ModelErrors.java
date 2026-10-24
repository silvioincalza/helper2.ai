package ai.helper2.platform.llm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Classifies exceptions from a model call. Types of Spring AI and of the OpenAI Java SDK (used by the
 * OpenAI-compatible client) are matched by simple name, anywhere in the cause chain and the class
 * hierarchy, so callers do not depend on one provider's SDK.
 *
 * <p>The SDK's own retry is off by default (spring.ai.openai.chat.max-retries, H2_LLM_MAX_RETRIES=0), so a
 * transient error reaches our code at once; a retry the SDK made would not be charged to any daily budget.
 */
public final class ModelErrors {

    /** Provider trouble that may fix itself later: no connection, a timeout, a 429 or a 5xx. */
    private static final Set<String> TRANSIENT = Set.of("TransientAiException", "RateLimitException",
        "InternalServerException", "OpenAIIoException", "OpenAIRetryableException");

    /** Base types of every error the provider client raises, transient or not (a 400, a 401, ...). */
    private static final Set<String> PROVIDER = Set.of("OpenAIException", "NonTransientAiException");

    private ModelErrors() {
    }

    public static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = next(t)) {
            if (t instanceof IOException || t instanceof TimeoutException || t instanceof ResourceAccessException
                || isA(t, TRANSIENT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the call failed at the provider (transient or rejected); false when the provider answered
     * and our side could not use the answer, e.g. JSON that does not match the requested schema.
     */
    public static boolean isProviderError(Throwable e) {
        if (isTransient(e)) {
            return true;
        }
        return has(e, PROVIDER);
    }

    /**
     * Why a call failed, as a fixed vocabulary for logs and metric tags: {@code timeout}, {@code rate_limited}
     * (a 429), {@code unavailable} (any other provider error), {@code parse_error} (the provider answered, but
     * not with the requested JSON) or {@code error}.
     */
    public static String category(Throwable e) {
        if (isTimeout(e)) {
            return "timeout";
        }
        if (has(e, Set.of("RateLimitException"))) {
            return "rate_limited";
        }
        if (isProviderError(e)) {
            return "unavailable";
        }
        return hasPackage(e, "tools.jackson") || hasPackage(e, "com.fasterxml.jackson") ? "parse_error" : "error";
    }

    /** OkHttp (the OpenAI SDK's client) reports its timeouts as InterruptedIOException("timeout"). */
    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = next(t)) {
            if (t instanceof TimeoutException || t instanceof SocketTimeoutException || t instanceof HttpTimeoutException
                || t instanceof InterruptedIOException || t.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }

    private static boolean has(Throwable e, Set<String> simpleNames) {
        for (Throwable t = e; t != null; t = next(t)) {
            if (isA(t, simpleNames)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPackage(Throwable e, String prefix) {
        for (Throwable t = e; t != null; t = next(t)) {
            if (t.getClass().getName().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isA(Throwable t, Set<String> simpleNames) {
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            if (simpleNames.contains(c.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static Throwable next(Throwable t) {
        return t.getCause() == t ? null : t.getCause();
    }
}
