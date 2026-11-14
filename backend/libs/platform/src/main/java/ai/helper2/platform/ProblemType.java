package ai.helper2.platform;

import java.net.URI;
import org.springframework.http.HttpStatus;

public enum ProblemType {
  INVALID_REQUEST("invalid-request", HttpStatus.BAD_REQUEST, "Invalid request"),
  SIGN_IN_REQUIRED("sign-in-required", HttpStatus.UNAUTHORIZED, "Sign-in required"),
  SIGN_IN_AGAIN("sign-in-again", HttpStatus.UNAUTHORIZED, "Sign in again"),
  FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "Forbidden"),
  NOT_FOUND("not-found", HttpStatus.NOT_FOUND, "Not found"),
  METHOD_NOT_ALLOWED("method-not-allowed", HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
  NOT_ACCEPTABLE("not-acceptable", HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
  UNSUPPORTED_MEDIA_TYPE(
      "unsupported-media-type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
  ALREADY_DECIDED("already-decided", HttpStatus.CONFLICT, "Already decided"),
  RUN_IN_PROGRESS("run-in-progress", HttpStatus.CONFLICT, "Run in progress"),
  VERSION_CONFLICT("version-conflict", HttpStatus.CONFLICT, "Changed by someone else"),
  DUPLICATE("duplicate", HttpStatus.CONFLICT, "Duplicate"),
  NOT_APPLICABLE("not-applicable", HttpStatus.CONFLICT, "Not applicable"),
  RATE_LIMITED("rate-limited", HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
  INTERNAL("internal", HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong"),
  UNAVAILABLE("unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
  TIMED_OUT("timed-out", HttpStatus.GATEWAY_TIMEOUT, "Timed out");

  public static final String BASE = "https://helper2.ai/problems/";

  private final String code;
  private final HttpStatus status;
  private final String title;

  ProblemType(String code, HttpStatus status, String title) {
    this.code = code;
    this.status = status;
    this.title = title;
  }

  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public String title() {
    return title;
  }

  public URI uri() {
    return URI.create(BASE + code);
  }

  public static ProblemType forStatus(int status) {
    return switch (status) {
      case 400 -> INVALID_REQUEST;
      case 401 -> SIGN_IN_REQUIRED;
      case 403 -> FORBIDDEN;
      case 404 -> NOT_FOUND;
      case 405 -> METHOD_NOT_ALLOWED;
      case 406 -> NOT_ACCEPTABLE;
      case 415 -> UNSUPPORTED_MEDIA_TYPE;
      case 429 -> RATE_LIMITED;
      case 503 -> UNAVAILABLE;
      case 504 -> TIMED_OUT;
      default -> status >= 500 ? INTERNAL : INVALID_REQUEST;
    };
  }
}
