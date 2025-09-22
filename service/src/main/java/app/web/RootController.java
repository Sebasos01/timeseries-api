package app.web;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {

  @GetMapping("/")
  public Map<String, Object> root() {
    return Map.of("service", "ts-service", "status", "ok");
  }

  @GetMapping("/v1/ping")
  public ResponseEntity<Map<String, Object>> ping() {
    return ResponseEntity.ok(Map.of("pong", true));
  }
}
