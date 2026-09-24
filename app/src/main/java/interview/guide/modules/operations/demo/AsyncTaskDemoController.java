package interview.guide.modules.operations.demo;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.result.Result;
import interview.guide.infrastructure.redis.RedisService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 仅在 isolated Profile 开启的无外部模型费用故障注入入口。
 */
@RestController
@Profile("isolated")
@RequestMapping("/api/demo/async")
public class AsyncTaskDemoController {

  private final RedisService redisService;
  private final AsyncTaskDemoState state;

  public AsyncTaskDemoController(RedisService redisService, AsyncTaskDemoState state) {
    this.redisService = redisService;
    this.state = state;
  }

  @PostMapping("/failures")
  public Result<DemoTaskResponse> createFailure() {
    String taskId = UUID.randomUUID().toString();
    Map<String, String> message = Map.of(
        AsyncTaskStreamConstants.FIELD_TASK_ID, taskId,
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT,
        String.valueOf(AsyncTaskStreamConstants.MAX_RETRY_COUNT),
        AsyncTaskStreamConstants.FIELD_ENQUEUED_AT,
        String.valueOf(System.currentTimeMillis()),
        AsyncTaskStreamConstants.FIELD_CONTENT,
        "synthetic private resume content for redaction verification"
    );
    String messageId = redisService.streamAdd(
        AsyncTaskDemoConsumer.STREAM_KEY,
        message,
        AsyncTaskStreamConstants.STREAM_MAX_LEN
    );
    state.mark(taskId, "QUEUED");
    return Result.success(new DemoTaskResponse(taskId, messageId, "QUEUED"));
  }

  @GetMapping("/tasks/{taskId}")
  public Result<DemoTaskResponse> status(@PathVariable String taskId) {
    return Result.success(new DemoTaskResponse(taskId, null, state.get(taskId)));
  }

  public record DemoTaskResponse(String taskId, String messageId, String status) {
  }
}
