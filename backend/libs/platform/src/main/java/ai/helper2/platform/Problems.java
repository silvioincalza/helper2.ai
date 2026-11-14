package ai.helper2.platform;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

public final class Problems {
  public static final String CODE = "code";

  private Problems() {}

  public static ProblemDetail of(ProblemType type, @Nullable String detail) {
    ProblemDetail problem = ProblemDetail.forStatus(type.status());
    return typed(problem, type, detail);
  }

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
