package ai.helper2.platform;

/** A thing the client named does not exist: a 404 problem of type not-found. */
public class NotFoundException extends ApiProblem {
  public NotFoundException(String message) {
    super(ProblemType.NOT_FOUND, message);
  }
}
