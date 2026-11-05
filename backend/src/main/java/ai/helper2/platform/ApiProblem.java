package ai.helper2.platform;

import org.jspecify.annotations.Nullable;

/**
 * An error the client should see as a problem of a known {@link ProblemType}. The detail is our own
 * text for a person; never input echoed back, never an internal message.
 */
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
