package app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

  @Override
  protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain) throws ServletException, IOException {
    long startTime = System.currentTimeMillis();
    try {
      filterChain.doFilter(request, response);
    } catch (ServletException | IOException ex) {
      log.error("Request {} {} from {} failed: {}",
          request.getMethod(),
          getRequestUriWithQuery(request),
          getClientIp(request),
          ex.getMessage(),
          ex);
      throw ex;
    } catch (RuntimeException ex) {
      log.error("Request {} {} from {} failed: {}",
          request.getMethod(),
          getRequestUriWithQuery(request),
          getClientIp(request),
          ex.getMessage(),
          ex);
      throw ex;
    } finally {
      long duration = System.currentTimeMillis() - startTime;
      log.info("HTTP {} {} from {} -> {} ({} ms)",
          request.getMethod(),
          getRequestUriWithQuery(request),
          getClientIp(request),
          response.getStatus(),
          duration);
    }
  }

  private String getRequestUriWithQuery(HttpServletRequest request) {
    String queryString = request.getQueryString();
    if (queryString == null || queryString.isBlank()) {
      return request.getRequestURI();
    }
    return request.getRequestURI() + "?" + queryString;
  }

  private String getClientIp(HttpServletRequest request) {
    String forwardedHeader = request.getHeader("X-Forwarded-For");
    if (forwardedHeader != null && !forwardedHeader.isBlank()) {
      return forwardedHeader.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}


