package ai.helper2.platform;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * The one way the API builds an RFC 9457 problem: Spring's {@link ProblemDetail} with the type URI,
 * title and status of a {@link ProblemType}, plus its stable {@code code} member.
 */
public final class Problems {

  /** The extension member with the stable code. */
  public static final String CODE = "code";

  private Problems() {}

  public static ProblemDetail of(ProblemType type, @Nullable String detail) {
    ProblemDetail problem = ProblemDetail.forStatus(type.status());
    return typed(problem, type, detail);
  }

  /** Sets the type, title and code of [type] on a problem the framework built. */
  public static ProblemDetail typed(
      ProblemDetail problem, ProblemType type, @Nullable String detail) {
    problem.setType(type.uri());
    problem.setTitle(type.title());
    if (detail != null) {
      problem.setDetail(detail);
    }
    problem.setProperty(CODE, type.code());
    return problem;
  }

  /**
   * The same problem as JSON, for the few places that answer before Spring MVC (servlet filters,
   * the rate-limit interceptor). [detail] is our own constant text.
   */
  public static String json(ProblemType type, @Nullable String detail) {
    StringBuilder json =
        new StringBuilder()
            .append("{\"type\":\"")
            .append(type.uri())
            .append("\",\"title\":\"")
            .append(escape(type.title()))
            .append("\",\"status\":")
            .append(type.status().value());
    if (detail != null) {
      json.append(",\"detail\":\"").append(escape(detail)).append('"');
    }
    return json.append(",\"code\":\"").append(type.code()).append("\"}").toString();
  }

  /**
   * Answers [type] on a response the framework would otherwise end with a bare status or its own
   * error page (the security filters' 401 and 403): the problem as JSON, with no caching.
   */
  public static void write(HttpServletResponse response, ProblemType type, @Nullable String detail)
      throws IOException {
    response.setStatus(type.status().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader("Cache-Control", "no-store");
    response.getWriter().write(json(type, detail));
  }

  private static String escape(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
