package ai.searchavo.platform;

public class BadRequestException extends ApiProblem {
  public BadRequestException(String message) {
    super(ProblemType.INVALID_REQUEST, message);
  }
}
