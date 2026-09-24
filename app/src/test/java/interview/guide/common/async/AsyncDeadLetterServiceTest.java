package interview.guide.common.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("异步任务死信服务")
class AsyncDeadLetterServiceTest {

  @Mock
  private RedisService redisService;
  @Mock
  private AsyncTaskMetrics metrics;

  private ObjectMapper objectMapper;
  private AsyncDeadLetterService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    service = new AsyncDeadLetterService(redisService, objectMapper, metrics);
  }

  @Test
  @DisplayName("失败任务同时写入审计Stream和带TTL的查询索引")
  void shouldPublishDeadLetterToStreamAndIndex() {
    when(redisService.streamAdd(
        eq(AsyncTaskStreamConstants.DEAD_LETTER_STREAM_KEY),
        any(),
        eq(AsyncTaskStreamConstants.DEAD_LETTER_STREAM_MAX_LEN)
    )).thenReturn("1-0");

    Optional<String> id = service.publish(
        "resume:analyze:stream",
        "analyze-group",
        "10-0",
        "resume_analyze_stream",
        3,
        "model timeout",
        Map.of("resumeId", "42", "content", "private resume")
    );

    assertThat(id).isPresent();
    verify(redisService).hSet(
        eq(AsyncTaskStreamConstants.DEAD_LETTER_INDEX_KEY),
        eq(id.orElseThrow()),
        anyString(),
        eq(Duration.ofDays(7))
    );
    verify(metrics).recordEvent("resume_analyze_stream", "dead_lettered");
  }

  @Test
  @DisplayName("人工重放重置重试次数并记录死信来源")
  void shouldReplayWithFreshMetadata() throws Exception {
    AsyncDeadLetterRecord record = new AsyncDeadLetterRecord(
        "dlq-1",
        "resume:analyze:stream",
        "analyze-group",
        "10-0",
        "resume_analyze_stream",
        3,
        "timeout",
        1000,
        AsyncDeadLetterRecord.STATUS_PENDING,
        null,
        null,
        Map.of("resumeId", "42", "retryCount", "3")
    );
    when(redisService.hGetFromCache(
        AsyncTaskStreamConstants.DEAD_LETTER_INDEX_KEY,
        "dlq-1"
    )).thenReturn(objectMapper.writeValueAsString(record));
    when(redisService.executeWithLock(anyString(), eq(1L), eq(15L), any(), any()))
        .thenAnswer(invocation -> {
          RedisService.LockedOperation<?> operation = invocation.getArgument(4);
          return operation.execute();
        });
    when(redisService.streamAdd(
        eq("resume:analyze:stream"), any(), anyInt()
    )).thenReturn("20-0");

    AsyncDeadLetterRecord replayed = service.replay("dlq-1");

    assertThat(replayed.status()).isEqualTo(AsyncDeadLetterRecord.STATUS_REPLAYED);
    assertThat(replayed.replayMessageId()).isEqualTo("20-0");
    ArgumentCaptor<Map<String, String>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
    verify(redisService).streamAdd(
        eq("resume:analyze:stream"),
        payloadCaptor.capture(),
        eq(AsyncTaskStreamConstants.STREAM_MAX_LEN)
    );
    assertThat(payloadCaptor.getValue())
        .containsEntry(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0")
        .containsEntry(AsyncTaskStreamConstants.FIELD_REPLAYED_FROM, "dlq-1")
        .containsKey(AsyncTaskStreamConstants.FIELD_ENQUEUED_AT);
    verify(metrics).recordReplay("resume_analyze_stream", "success");
  }

  @Test
  @DisplayName("已经重放的任务不能重复重放")
  void shouldRejectDuplicateReplay() throws Exception {
    AsyncDeadLetterRecord record = new AsyncDeadLetterRecord(
        "dlq-1", "source", "group", "10-0", "task", 3, "error", 1000,
        AsyncDeadLetterRecord.STATUS_REPLAYED, "20-0", 2000L, Map.of("id", "1"));
    when(redisService.hGetFromCache(
        AsyncTaskStreamConstants.DEAD_LETTER_INDEX_KEY,
        "dlq-1"
    )).thenReturn(objectMapper.writeValueAsString(record));
    when(redisService.executeWithLock(anyString(), eq(1L), eq(15L), any(), any()))
        .thenAnswer(invocation -> {
          RedisService.LockedOperation<?> operation = invocation.getArgument(4);
          return operation.execute();
        });

    assertThatThrownBy(() -> service.replay("dlq-1"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不可重放");
  }
}
