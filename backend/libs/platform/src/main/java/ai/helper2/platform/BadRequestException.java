package ai.helper2.platform;

public class BadRequestException extends ApiProblem {
  public BadRequestException(String message) {
    super(ProblemType.INVALID_REQUEST, message);
  }
}
