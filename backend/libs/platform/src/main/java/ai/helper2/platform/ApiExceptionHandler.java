package ai.helper2.platform;

import jakarta.validation.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 problem details with Spring's own {@link ProblemDetail} (ADR 0003): every answer has a
 * {@link ProblemType} URI and its stable {@code code}. Never echoes internals or input back. Only
 * what the client sent can be a 400; every other exception is a bug or an outage and answers 500.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** What a 400 says, whatever the caller sent: input is never echoed back. */
  static final String INVALID = "Invalid request parameters.";

  @ExceptionHandler(BadRequestException.class)
  ProblemDetail badRequest(BadRequestException e) {
    return Problems.of(ProblemType.INVALID_REQUEST, INVALID);
  }

  @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
  ProblemDetail invalidParameters(Exception e) {
    return Problems.of(ProblemType.INVALID_REQUEST, INVALID);
  }

  @ExceptionHandler(ApiProblem.class)
  ProblemDetail problem(ApiProblem e) {
    return Problems.of(e.type(), e.detail());
  }

  @ExceptionHandler(RuntimeException.class)
  ProblemDetail unexpected(RuntimeException e) {
    log.error("Unhandled exception in a request", e);
    return Problems.of(ProblemType.INTERNAL, "Something went wrong on our side.");
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return ResponseEntity.badRequest().body(Problems.of(ProblemType.INVALID_REQUEST, INVALID));
  }

  /**
   * The errors Spring answers on its own (unreadable body, method not allowed, ...): the same
   * problem, typed by its status. Here, not in handleExceptionInternal: Spring builds the body of a
   * 404, 405 or 415 only after that method, so this is the first place every body exists.
   */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (body instanceof ProblemDetail problem) {
      ProblemType type = ProblemType.forStatus(status.value());
      Problems.typed(problem, type, type == ProblemType.INVALID_REQUEST ? INVALID : null);
    }
    return super.createResponseEntity(body, headers, status, request);
  }
}
