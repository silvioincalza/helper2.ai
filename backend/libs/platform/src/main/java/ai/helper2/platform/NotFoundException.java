package ai.helper2.platform;

public class NotFoundException extends ApiProblem {
  public NotFoundException(String message) {
    super(ProblemType.NOT_FOUND, message);
  }
}
