package ai.helper2.platform;

/**
 * Invalid input from the client: a 400 problem of type invalid-request. Throw it only for what the
 * caller sent; any other exception (IllegalArgumentException from a bug included) is a 500. The
 * message is for the logs: the client gets a fixed detail, so input is never echoed back.
 */
public class BadRequestException extends ApiProblem {
  public BadRequestException(String message) {
    super(ProblemType.INVALID_REQUEST, message);
  }
}
