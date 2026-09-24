package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis Stream 生产者模板基类。
 * 统一消息发送骨架与失败处理逻辑。
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {

    private final RedisService redisService;
    private final AsyncTaskMetrics metrics;

    protected AbstractStreamProducer(RedisService redisService, AsyncTaskMetrics metrics) {
        this.redisService = redisService;
        this.metrics = metrics;
    }

    protected boolean sendTask(T payload) {
        long startNanos = System.nanoTime();
        try {
            Map<String, String> message = new HashMap<>(buildMessage(payload));
            message.put(
                AsyncTaskStreamConstants.FIELD_ENQUEUED_AT,
                String.valueOf(System.currentTimeMillis())
            );
            String messageId = redisService.streamAdd(
                streamKey(),
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            metrics.recordEnqueue(metricTaskName(), "success", System.nanoTime() - startNanos);
            log.info("{}任务已发送到Stream: {}, messageId={}",
                taskDisplayName(), payloadIdentifier(payload), messageId);
            return true;
        } catch (Exception e) {
            metrics.recordEnqueue(metricTaskName(), "failure", System.nanoTime() - startNanos);
            log.error("发送{}任务失败: {}, error={}",
                taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            onSendFailed(payload, "任务入队失败: " + e.getMessage());
            return false;
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    protected abstract String taskDisplayName();

    protected String metricTaskName() {
        return streamKey().replace(':', '_');
    }

    protected abstract String streamKey();

    protected abstract Map<String, String> buildMessage(T payload);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void onSendFailed(T payload, String error);
}
