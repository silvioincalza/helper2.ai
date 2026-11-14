package ai.helper2.platform.llm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.springframework.web.client.ResourceAccessException;

public final class ModelErrors {
  private static final Set<String> TRANSIENT =
      Set.of(
          "TransientAiException",
          "RateLimitException",
          "InternalServerException",
          "OpenAIIoException",
          "OpenAIRetryableException");

  private static final Set<String> PROVIDER = Set.of("OpenAIException", "NonTransientAiException");

  private ModelErrors() {}

  public static boolean isTransient(Throwable e) {
    for (Throwable t = e; t != null; t = next(t)) {
      if (t instanceof IOException
          || t instanceof TimeoutException
          || t instanceof ResourceAccessException
          || isA(t, TRANSIENT)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isProviderError(Throwable e) {
    if (isTransient(e)) {
      return true;
    }
    return has(e, PROVIDER);
  }

  public static boolean isInterrupt(Throwable e) {
    if (Thread.currentThread().isInterrupted()) {
      return true;
    }
    for (Throwable t = e; t != null; t = next(t)) {
      if (t instanceof InterruptedException
          || t instanceof ClosedByInterruptException
          || isInterruptedIo(t)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isInterruptedIo(Throwable t) {
    return t.getClass() == InterruptedIOException.class
        && (t.getMessage() == null || !t.getMessage().toLowerCase(Locale.ROOT).contains("timeout"));
  }

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
    return hasPackage(e, "tools.jackson") || hasPackage(e, "com.fasterxml.jackson")
        ? "parse_error"
        : "error";
  }

  public static final class NotFromProvider implements Predicate<Throwable> {
    @Override
    public boolean test(Throwable e) {
      return !isProviderError(e) || isInterrupt(e);
    }
  }

  private static boolean isTimeout(Throwable e) {
    for (Throwable t = e; t != null; t = next(t)) {
      if (t instanceof TimeoutException
          || t instanceof SocketTimeoutException
          || t instanceof HttpTimeoutException
          || t instanceof InterruptedIOException
          || t.getClass().getSimpleName().contains("Timeout")) {
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
