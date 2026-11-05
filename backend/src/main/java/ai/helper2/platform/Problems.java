package ai.helper2.platform;

import org.jspecify.annotations.Nullable;
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

  private static String escape(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
