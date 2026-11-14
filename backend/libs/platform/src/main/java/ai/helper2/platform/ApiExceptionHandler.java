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

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
