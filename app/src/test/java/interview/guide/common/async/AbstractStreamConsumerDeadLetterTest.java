package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.stream.StreamMessageId;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("异步消费者死信语义")
class AbstractStreamConsumerDeadLetterTest {

  @Mock
  private RedisService redisService;
  @Mock
  private AsyncTaskMetrics metrics;
  @Mock
  private AsyncDeadLetterService deadLetterService;

  @Test
  @DisplayName("超过重试上限后先写死信再标记失败并ACK")
  void shouldDeadLetterBeforeAckAfterRetriesExhausted() throws Exception {
    TestConsumer consumer = new TestConsumer(redisService, metrics, deadLetterService);
    consumer.failBusiness = true;
    when(deadLetterService.publish(
        any(), any(), any(), any(), anyInt(), any(), any()
    )).thenReturn(Optional.of("dlq-1"));
    StreamMessageId messageId = new StreamMessageId(10, 0);

    invokeProcess(consumer, messageId, message(3));

    assertThat(consumer.failed).isTrue();
    verify(redisService).streamAck("test:stream", "test-group", messageId);
    verify(deadLetterService).publish(
        eq("test:stream"),
        eq("test-group"),
        eq(messageId.toString()),
        eq("test_stream"),
        eq(3),
        any(),
        any()
    );
  }

  @Test
  @DisplayName("死信写入失败时不ACK原消息")
  void shouldKeepOriginalPendingWhenDeadLetterPublishFails() throws Exception {
    TestConsumer consumer = new TestConsumer(redisService, metrics, deadLetterService);
    consumer.failBusiness = true;
    when(deadLetterService.publish(
        any(), any(), any(), any(), anyInt(), any(), any()
    )).thenReturn(Optional.empty());
    StreamMessageId messageId = new StreamMessageId(10, 0);

    invokeProcess(consumer, messageId, message(3));

    assertThat(consumer.failed).isFalse();
    verify(redisService, never()).streamAck(eq("test:stream"), eq("test-group"), any());
  }

  @Test
  @DisplayName("无法解析的消息进入死信而不是静默丢弃")
  void shouldDeadLetterMalformedMessage() throws Exception {
    TestConsumer consumer = new TestConsumer(redisService, metrics, deadLetterService);
    when(deadLetterService.publish(
        any(), any(), any(), any(), anyInt(), any(), any()
    )).thenReturn(Optional.of("dlq-2"));
    StreamMessageId messageId = new StreamMessageId(11, 0);

    invokeProcess(consumer, messageId, Map.of("wrong", "value"));

    verify(deadLetterService).publish(
        "test:stream",
        "test-group",
        messageId.toString(),
        "test_stream",
        0,
        "消息字段缺失或格式错误",
        Map.of("wrong", "value")
    );
    verify(redisService).streamAck("test:stream", "test-group", messageId);
  }

  private Map<String, String> message(int retryCount) {
    return Map.of(
        "id", "42",
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount),
        AsyncTaskStreamConstants.FIELD_ENQUEUED_AT,
        String.valueOf(System.currentTimeMillis() - 25)
    );
  }

  private void invokeProcess(
      AbstractStreamConsumer<?> consumer,
      StreamMessageId messageId,
      Map<String, String> data
  ) throws Exception {
    Method method = AbstractStreamConsumer.class.getDeclaredMethod(
        "processMessage", StreamMessageId.class, Map.class);
    method.setAccessible(true);
    method.invoke(consumer, messageId, data);
  }

  private static final class TestConsumer extends AbstractStreamConsumer<String> {

    private boolean failBusiness;
    private boolean failed;

    private TestConsumer(
        RedisService redisService,
        AsyncTaskMetrics metrics,
        AsyncDeadLetterService deadLetterService
    ) {
      super(redisService, metrics, deadLetterService);
    }

    @Override
    protected String taskDisplayName() {
      return "测试任务";
    }

    @Override
    protected String streamKey() {
      return "test:stream";
    }

    @Override
    protected String groupName() {
      return "test-group";
    }

    @Override
    protected String consumerPrefix() {
      return "test-consumer-";
    }

    @Override
    protected String threadName() {
      return "test-consumer";
    }

    @Override
    protected String parsePayload(StreamMessageId messageId, Map<String, String> data) {
      return data.get("id");
    }

    @Override
    protected String payloadIdentifier(String payload) {
      return payload;
    }

    @Override
    protected void markProcessing(String payload) {
    }

    @Override
    protected void processBusiness(String payload) {
      if (failBusiness) {
        throw new IllegalStateException("simulated failure");
      }
    }

    @Override
    protected void markCompleted(String payload) {
    }

    @Override
    protected void markFailed(String payload, String error) {
      failed = true;
    }

    @Override
    protected boolean retryMessage(String payload, int retryCount) {
      return true;
    }
  }
}
