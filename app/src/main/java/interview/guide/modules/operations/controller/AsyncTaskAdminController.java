package interview.guide.modules.operations.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.async.AsyncDeadLetterRecord;
import interview.guide.common.async.AsyncDeadLetterService;
import interview.guide.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步任务运维接口。生产环境应在网关或安全模块中限制为管理员访问。
 */
@RestController
@RequestMapping("/api/admin/async/dead-letters")
public class AsyncTaskAdminController {

  private static final int MAX_VISIBLE_VALUE_LENGTH = 120;

  private final AsyncDeadLetterService deadLetterService;

  public AsyncTaskAdminController(AsyncDeadLetterService deadLetterService) {
    this.deadLetterService = deadLetterService;
  }

  @GetMapping
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
  public Result<List<DeadLetterResponse>> list(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int limit
  ) {
    return Result.success(deadLetterService.list(status, limit).stream()
        .map(DeadLetterResponse::from)
        .toList());
  }

  @GetMapping("/stats")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
  public Result<AsyncDeadLetterService.AsyncDeadLetterStats> stats() {
    return Result.success(deadLetterService.stats());
  }

  @PostMapping("/{deadLetterId}/replay")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
  public Result<DeadLetterResponse> replay(@PathVariable String deadLetterId) {
    return Result.success(DeadLetterResponse.from(deadLetterService.replay(deadLetterId)));
  }

  public record DeadLetterResponse(
      String id,
      String sourceStream,
      String consumerGroup,
      String originalMessageId,
      String task,
      int retryCount,
      String error,
      long failedAtEpochMs,
      String status,
      String replayMessageId,
      Long replayedAtEpochMs,
      Map<String, String> payloadSummary
  ) {

    static DeadLetterResponse from(AsyncDeadLetterRecord record) {
      return new DeadLetterResponse(
          record.id(),
          record.sourceStream(),
          record.consumerGroup(),
          record.originalMessageId(),
          record.task(),
          record.retryCount(),
          truncate(record.error()),
          record.failedAtEpochMs(),
          record.status(),
          record.replayMessageId(),
          record.replayedAtEpochMs(),
          summarize(record.payload())
      );
    }

    private static Map<String, String> summarize(Map<String, String> payload) {
      Map<String, String> summary = new LinkedHashMap<>();
      payload.forEach((key, value) -> {
        if ("content".equalsIgnoreCase(key)) {
          summary.put(key, "<redacted:" + value.length() + " chars>");
        } else {
          summary.put(key, truncate(value));
        }
      });
      return Map.copyOf(summary);
    }

    private static String truncate(String value) {
      if (value == null || value.length() <= MAX_VISIBLE_VALUE_LENGTH) {
        return value;
      }
      return value.substring(0, MAX_VISIBLE_VALUE_LENGTH) + "...";
    }
  }
}
