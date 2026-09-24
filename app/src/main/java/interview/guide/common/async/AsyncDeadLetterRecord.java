package interview.guide.common.async;

import java.util.Map;

/**
 * 可查询、可重放的异步任务死信记录。
 */
public record AsyncDeadLetterRecord(
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
    Map<String, String> payload
) {

  public static final String STATUS_PENDING = "PENDING";
  public static final String STATUS_REPLAYING = "REPLAYING";
  public static final String STATUS_REPLAYED = "REPLAYED";

  public AsyncDeadLetterRecord {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  public AsyncDeadLetterRecord markReplaying() {
    return new AsyncDeadLetterRecord(
        id, sourceStream, consumerGroup, originalMessageId, task, retryCount, error,
        failedAtEpochMs, STATUS_REPLAYING, null, null, payload);
  }

  public AsyncDeadLetterRecord markReplayed(String newMessageId, long replayedAt) {
    return new AsyncDeadLetterRecord(
        id, sourceStream, consumerGroup, originalMessageId, task, retryCount, error,
        failedAtEpochMs, STATUS_REPLAYED, newMessageId, replayedAt, payload);
  }

  public AsyncDeadLetterRecord markPending() {
    return new AsyncDeadLetterRecord(
        id, sourceStream, consumerGroup, originalMessageId, task, retryCount, error,
        failedAtEpochMs, STATUS_PENDING, null, null, payload);
  }
}
