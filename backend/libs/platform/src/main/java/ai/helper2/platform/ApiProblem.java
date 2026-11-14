package ai.helper2.platform;

import org.jspecify.annotations.Nullable;

public class ApiProblem extends RuntimeException {

  private final ProblemType type;

  public ApiProblem(ProblemType type, @Nullable String detail) {
    super(detail == null ? type.title() : detail);
    this.type = type;
  }

  public ProblemType type() {
    return type;
  }

  public String detail() {
    return getMessage();
  }
}
