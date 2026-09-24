package interview.guide.modules.operations.demo;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.AsyncDeadLetterService;
import interview.guide.common.async.AsyncTaskMetrics;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 隔离演示环境专用消费者：首次处理必定失败，死信重放后成功。
 */
@Component
@Profile("isolated")
public class AsyncTaskDemoConsumer
    extends AbstractStreamConsumer<AsyncTaskDemoConsumer.DemoPayload> {

  public static final String STREAM_KEY = "demo:async:stream";
  public static final String GROUP_NAME = "demo-async-group";

  private final AsyncTaskDemoState state;

  public AsyncTaskDemoConsumer(
      RedisService redisService,
      AsyncTaskMetrics metrics,
      AsyncDeadLetterService deadLetterService,
      AsyncTaskDemoState state
  ) {
    super(redisService, metrics, deadLetterService);
    this.state = state;
  }

  record DemoPayload(String taskId, boolean replayed) {
  }

  @Override
  protected String taskDisplayName() {
    return "死信演示";
  }

  @Override
  protected String streamKey() {
    return STREAM_KEY;
  }

  @Override
  protected String groupName() {
    return GROUP_NAME;
  }

  @Override
  protected String consumerPrefix() {
    return "demo-async-consumer-";
  }

  @Override
  protected String threadName() {
    return "demo-async-consumer";
  }

  @Override
  protected DemoPayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
    String taskId = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
    if (taskId == null) {
      return null;
    }
    return new DemoPayload(
        taskId,
        data.containsKey(AsyncTaskStreamConstants.FIELD_REPLAYED_FROM)
    );
  }

  @Override
  protected String payloadIdentifier(DemoPayload payload) {
    return "taskId=" + payload.taskId();
  }

  @Override
  protected void markProcessing(DemoPayload payload) {
    state.mark(payload.taskId(), "PROCESSING");
  }

  @Override
  protected void processBusiness(DemoPayload payload) {
    if (!payload.replayed()) {
      throw new IllegalStateException("isolated demo failure");
    }
  }

  @Override
  protected void markCompleted(DemoPayload payload) {
    state.mark(payload.taskId(), "COMPLETED");
  }

  @Override
  protected void markFailed(DemoPayload payload, String error) {
    state.mark(payload.taskId(), "FAILED");
  }

  @Override
  protected boolean retryMessage(DemoPayload payload, int retryCount) {
    return false;
  }
}
