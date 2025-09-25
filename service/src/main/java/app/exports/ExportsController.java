package app.exports;

import java.util.Collections;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class ExportsController {

  @PostMapping("/exports")
  public ResponseEntity<List<?>> exports() {
    return ResponseEntity.ok(Collections.emptyList());
  }
}