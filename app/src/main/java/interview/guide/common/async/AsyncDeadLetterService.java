package interview.guide.common.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 异步任务死信留存、查询与人工重放服务。
 *
 * <p>死信 Stream 用于审计，Hash 索引用于按ID查询和更新重放状态。重放遵循
 * at-least-once 语义，业务消费者仍需依靠任务状态实现幂等。</p>
 */
@Slf4j
@Service
public class AsyncDeadLetterService {

  private static final int MAX_QUERY_LIMIT = 200;
  private static final int MAX_ERROR_LENGTH = 1000;
  private static final Duration RECORD_TTL = Duration.ofDays(7);

  private final RedisService redisService;
  private final ObjectMapper objectMapper;
  private final AsyncTaskMetrics metrics;

  public AsyncDeadLetterService(
      RedisService redisService,
      ObjectMapper objectMapper,
      AsyncTaskMetrics metrics
  ) {
    this.redisService = redisService;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
  }

  public Optional<String> publish(
      String sourceStream,
      String consumerGroup,
      String originalMessageId,
      String task,
      int retryCount,
      String error,
      Map<String, String> payload
  ) {
    String deadLetterId = UUID.randomUUID().toString();
    long failedAt = System.currentTimeMillis();
    AsyncDeadLetterRecord record = new AsyncDeadLetterRecord(
        deadLetterId,
        sourceStream,
        consumerGroup,
        originalMessageId,
        task,
        retryCount,
        truncate(error),
        failedAt,
        AsyncDeadLetterRecord.STATUS_PENDING,
        null,
        null,
        payload
    );

    try {
      String payloadJson = objectMapper.writeValueAsString(record.payload());
      Map<String, String> auditMessage = new HashMap<>();
      auditMessage.put("deadLetterId", deadLetterId);
      auditMessage.put("sourceStream", sourceStream);
      auditMessage.put("consumerGroup", consumerGroup);
      auditMessage.put("originalMessageId", originalMessageId);
      auditMessage.put("task", task);
      auditMessage.put("retryCount", String.valueOf(retryCount));
      auditMessage.put("error", record.error());
      auditMessage.put("failedAtEpochMs", String.valueOf(failedAt));
      auditMessage.put("payloadJson", payloadJson);

      redisService.streamAdd(
          AsyncTaskStreamConstants.DEAD_LETTER_STREAM_KEY,
          auditMessage,
          AsyncTaskStreamConstants.DEAD_LETTER_STREAM_MAX_LEN
      );
      save(record);
      metrics.recordEvent(task, "dead_lettered");
      log.warn("异步任务进入死信流: deadLetterId={}, task={}, originalMessageId={}",
          deadLetterId, task, originalMessageId);
      return Optional.of(deadLetterId);
    } catch (Exception e) {
      metrics.recordEvent(task, "dead_letter_publish_failed");
      log.error("异步任务写入死信流失败: task={}, originalMessageId={}",
          task, originalMessageId, e);
      return Optional.empty();
    }
  }

  public List<AsyncDeadLetterRecord> list(String status, int requestedLimit) {
    validateStatus(status);
    int limit = Math.max(1, Math.min(requestedLimit, MAX_QUERY_LIMIT));
    Map<String, String> rows = redisService.hGetAllFromCache(
        AsyncTaskStreamConstants.DEAD_LETTER_INDEX_KEY);
    List<AsyncDeadLetterRecord> records = new ArrayList<>();
    for (String json : rows.values()) {
      try {
        AsyncDeadLetterRecord record = objectMapper.readValue(json, AsyncDeadLetterRecord.class);
        if (status == null || status.isBlank() || record.status().equalsIgnoreCase(status)) {
          records.add(record);
        }
      } catch (JsonProcessingException e) {
        log.error("无法解析死信索引记录", e);
      }
    }
    return records.stream()
        .sorted(Comparator.comparingLong(AsyncDeadLetterRecord::failedAtEpochMs).reversed())
        .limit(limit)
        .toList();
  }

  public AsyncDeadLetterStats stats() {
    Map<String, String> rows = redisService.hGetAllFromCache(
        AsyncTaskStreamConstants.DEAD_LETTER_INDEX_KEY);
    long pending = 0;
    long replaying = 0;
    long replayed = 0;
    for (String json : rows.values()) {
      try {
        String status = objectMapper.readValue(json, AsyncDeadLetterRecord.class).status();
        if (AsyncDeadLetterRecord.STATUS_PENDING.equals(status)) {
          pending++;
        } else if (AsyncDeadLetterRecord.STATUS_REPLAYING.equals(status)) {
          replaying++;
        } else if (AsyncDeadLetterRecord.STATUS_REPLAYED.equals(status)) {
          replayed++;
        }
      } catch (JsonProcessingException e) {
        log.error("无法解析死信索引记录", e);
      }
    }
    return new AsyncDeadLetterStats(
        rows.size(),
        pending,
        replaying,
        replayed,
        redisService.streamLen(AsyncTaskStreamConstants.DEAD_LETTER_STREAM_KEY)
    );
  }

  public AsyncDeadLetterRecord replay(String deadLetterId) {
    String lockKey = AsyncTaskStreamConstants.DEAD_LETTER_REPLAY_LOCK_PREFIX + deadLetterId;
    return redisService.executeWithLock(
        lockKey,
        1,
        15,
        TimeUnit.SECONDS,
        () -> replayLocked(deadLetterId)
    );
  }

  private AsyncDeadLetterRecord replayLocked(String deadLetterId) {
    AsyncDeadLetterRecord record = find(deadLetterId);
    if (!AsyncDeadLetterRecord.STATUS_PENDING.equals(record.status())) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "死信任务当前状态不可重放: " + record.status()
      );
    }

    save(record.markReplaying());
    Map<String, String> replayPayload = new HashMap<>(record.payload());
    replayPayload.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
    replayPayload.put(
        AsyncTaskStreamConstants.FIELD_ENQUEUED_AT,
        String.valueOf(System.currentTimeMillis())
    );
    replayPayload.put(AsyncTaskStreamConstants.FIELD_REPLAYED_FROM, deadLetterId);

    try {
      String messageId = redisService.streamAdd(
          record.sourceStream(),
          replayPayload,
          AsyncTaskStreamConstants.STREAM_MAX_LEN
      );
      AsyncDeadLetterRecord replayed = record.markReplayed(
          messageId,
          System.currentTimeMillis()
      );
      save(replayed);
      metrics.recordReplay(record.task(), "success");
      log.info("死信任务已重放: deadLetterId={}, newMessageId={}", deadLetterId, messageId);
      return replayed;
    } catch (Exception e) {
      save(record.markPending());
      metrics.recordReplay(record.task(), "failure");
      log.error("死信任务重放失败: deadLetterId={}", deadLetterId, e);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "死信任务重放失败", e);
    }
  }

  private AsyncDeadLetterRecord find(String deadLetterId) {
    String json = redisService.hGetFromCache(
        AsyncTaskStreamConstants.DEAD_LETTER_INDEX_KEY,
        deadLetterId
    );
    if (json == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "死信任务不存在");
    }
    try {
      return objectMapper.readValue(json, AsyncDeadLetterRecord.class);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "死信任务数据损坏", e);
    }
  }

  private void save(AsyncDeadLetterRecord record) {
    try {
      redisService.hSet(
          AsyncTaskStreamConstants.DEAD_LETTER_INDEX_KEY,
          record.id(),
          objectMapper.writeValueAsString(record),
          RECORD_TTL
      );
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "死信任务序列化失败", e);
    }
  }

  private String truncate(String error) {
    if (error == null || error.isBlank()) {
      return "unknown error";
    }
    return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
  }

  private void validateStatus(String status) {
    if (status == null || status.isBlank()) {
      return;
    }
    if (!AsyncDeadLetterRecord.STATUS_PENDING.equalsIgnoreCase(status)
        && !AsyncDeadLetterRecord.STATUS_REPLAYING.equalsIgnoreCase(status)
        && !AsyncDeadLetterRecord.STATUS_REPLAYED.equalsIgnoreCase(status)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的死信状态: " + status);
    }
  }

  public record AsyncDeadLetterStats(
      long total,
      long pending,
      long replaying,
      long replayed,
      long auditStreamLength
  ) {
  }
}
