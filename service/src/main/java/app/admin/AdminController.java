package app.admin;

import app.series.SeriesSyncService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {
  private final SeriesSyncService syncService;

  public AdminController(SeriesSyncService syncService) {
    this.syncService = syncService;
  }

  @PostMapping("/reindex")
  public ResponseEntity<Map<String, String>> reindex() {
    syncService.reindexAllAsync();
    return ResponseEntity.accepted().body(Map.of("status", "reindex started"));
  }
}
