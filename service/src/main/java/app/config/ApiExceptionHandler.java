package app.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Map<HttpStatus, String> TYPE_SLUGS = Map.of(
      HttpStatus.BAD_REQUEST, "invalid-parameter",
      HttpStatus.UNAUTHORIZED, "unauthorized",
      HttpStatus.FORBIDDEN, "forbidden",
      HttpStatus.NOT_FOUND, "not-found",
      HttpStatus.TOO_MANY_REQUESTS, "rate-limit",
      HttpStatus.INTERNAL_SERVER_ERROR, "internal-error"
  );

  @ExceptionHandler({ConstraintViolationException.class, MethodArgumentNotValidException.class,
      MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
  public ResponseEntity<ProblemDetail> handleBadRequest(Exception ex, HttpServletRequest request) {
    return buildProblem(HttpStatus.BAD_REQUEST, ex, request);
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ProblemDetail> handleNotFound(NoSuchElementException ex,
      HttpServletRequest request) {
    return buildProblem(HttpStatus.NOT_FOUND, ex, request);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleUnauthorized(AuthenticationException ex,
      HttpServletRequest request) {
    return buildProblem(HttpStatus.UNAUTHORIZED, ex, request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleForbidden(AccessDeniedException ex,
      HttpServletRequest request) {
    return buildProblem(HttpStatus.FORBIDDEN, ex, request);
  }

  @ExceptionHandler(TooManyRequestsException.class)
  public ResponseEntity<ProblemDetail> handleTooManyRequests(TooManyRequestsException ex,
      HttpServletRequest request) {
    return buildProblem(HttpStatus.TOO_MANY_REQUESTS, ex, request);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex,
      HttpServletRequest request) {
    HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
    if (status == null) {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
    }
    return buildProblem(status, ex, request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleServerError(Exception ex, HttpServletRequest request) {
    return buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, ex, request);
  }

  private ResponseEntity<ProblemDetail> buildProblem(HttpStatus status, Exception ex,
      HttpServletRequest request) {
    ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
    detail.setTitle(status.getReasonPhrase());
    detail.setInstance(URI.create(request.getRequestURI()));
    detail.setType(URI.create("https://docs.timeseries-api.dev/problems/" +
        TYPE_SLUGS.getOrDefault(status, "internal-error")));
    detail.setProperty("path", request.getRequestURI());
    return ResponseEntity.status(status).body(detail);
  }
}
