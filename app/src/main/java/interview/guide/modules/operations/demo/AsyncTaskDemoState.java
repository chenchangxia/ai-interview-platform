package interview.guide.modules.operations.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("isolated")
public class AsyncTaskDemoState {

  private final Map<String, String> statuses = new ConcurrentHashMap<>();

  public void mark(String taskId, String status) {
    statuses.put(taskId, status);
  }

  public String get(String taskId) {
    return statuses.getOrDefault(taskId, "UNKNOWN");
  }
}
