package dev.zhiku.platform;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class StatusController {
 @GetMapping("/api/status") public Map<String,Object> status() {
  return Map.of("name", "知库", "stage", "scaffold", "prdVersion", "0.3", "modelEnabled", false);
 }
}
