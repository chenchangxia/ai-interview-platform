package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    private final AsyncTaskMetrics metrics;
    private final AsyncDeadLetterService deadLetterService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private String consumerName;

    protected AbstractStreamConsumer(
        RedisService redisService,
        AsyncTaskMetrics metrics,
        AsyncDeadLetterService deadLetterService
    ) {
        this.redisService = redisService;
        this.metrics = metrics;
        this.deadLetterService = deadLetterService;
    }

    @PostConstruct
    public void init() {
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        this.executorService = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, threadName());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

        running.set(true);
        executorService.submit(this::startConsumer);
        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    private void startConsumer() {
        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

        consumeLoop();
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS,
                    AsyncTaskStreamConstants.PENDING_CLAIM_BATCH_SIZE,
                    this::processMessage
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            }
        }
    }

    private void processMessage(StreamMessageId messageId, Map<String, String> data) {
        String task = metricTaskName();
        long processingStartNanos = System.nanoTime();
        metrics.recordQueueWait(task, calculateQueueWaitMillis(data));
        T payload;
        try {
            payload = parsePayload(messageId, data);
        } catch (Exception e) {
            Object fields = data == null ? null : data.keySet();
            log.warn("Failed to parse {} stream message, ack and discard: messageId={}, fields={}",
                taskDisplayName(), messageId, fields, e);
            metrics.recordEvent(task, "invalid");
            boolean published = publishDeadLetter(messageId, data, 0,
                "消息解析失败: " + e.getMessage());
            if (published) {
                ackMessage(messageId);
            }
            metrics.recordProcessing(task, published ? "dead_lettered" : "dlq_failure",
                System.nanoTime() - processingStartNanos);
            return;
        }

        if (payload == null) {
            metrics.recordEvent(task, "invalid");
            boolean published = publishDeadLetter(messageId, data, parseRetryCount(data),
                "消息字段缺失或格式错误");
            if (published) {
                ackMessage(messageId);
            }
            metrics.recordProcessing(task, published ? "dead_lettered" : "dlq_failure",
                System.nanoTime() - processingStartNanos);
            return;
        }

        int retryCount = parseRetryCount(data);
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

        try {
            if (shouldSkip(payload)) {
                ackMessage(messageId);
                metrics.recordEvent(task, "skipped");
                metrics.recordProcessing(task, "skipped", System.nanoTime() - processingStartNanos);
                log.info("{} task skipped: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            if (!tryMarkProcessing(payload)) {
                ackMessage(messageId);
                metrics.recordEvent(task, "not_claimed");
                metrics.recordProcessing(task, "not_claimed", System.nanoTime() - processingStartNanos);
                log.info("{} task was not claimed: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            processBusiness(payload);
            markCompleted(payload);
            ackMessage(messageId);
            metrics.recordEvent(task, "completed");
            metrics.recordProcessing(task, "completed", System.nanoTime() - processingStartNanos);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                boolean requeued = retryMessage(payload, retryCount + 1);
                if (requeued) {
                    metrics.recordEvent(task, "retried");
                    ackMessage(messageId);
                    metrics.recordProcessing(task, "retried", System.nanoTime() - processingStartNanos);
                    return;
                }
                String error = truncateError("重试入队失败: " + e.getMessage());
                boolean published = publishDeadLetter(messageId, data, retryCount, error);
                if (published) {
                    markFailed(payload, error);
                    ackMessage(messageId);
                }
                metrics.recordProcessing(task, published ? "dead_lettered" : "dlq_failure",
                    System.nanoTime() - processingStartNanos);
            } else {
                String error = truncateError(
                    taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage());
                boolean published = publishDeadLetter(messageId, data, retryCount, error);
                if (published) {
                    markFailed(payload, error);
                    ackMessage(messageId);
                }
                metrics.recordProcessing(task, published ? "dead_lettered" : "dlq_failure",
                    System.nanoTime() - processingStartNanos);
            }
        }
    }

    private boolean publishDeadLetter(
        StreamMessageId messageId,
        Map<String, String> data,
        int retryCount,
        String error
    ) {
        return deadLetterService.publish(
            streamKey(),
            groupName(),
            messageId.toString(),
            metricTaskName(),
            retryCount,
            error,
            data == null ? Map.of() : data
        ).isPresent();
    }

    private long calculateQueueWaitMillis(Map<String, String> data) {
        if (data == null) {
            return -1;
        }
        try {
            long enqueuedAt = Long.parseLong(
                data.getOrDefault(AsyncTaskStreamConstants.FIELD_ENQUEUED_AT, "-1"));
            return enqueuedAt < 0 ? -1 : Math.max(0, System.currentTimeMillis() - enqueuedAt);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    protected int parseRetryCount(Map<String, String> data) {
        if (data == null) {
            return 0;
        }
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    protected RedisService redisService() {
        return redisService;
    }

    protected abstract String taskDisplayName();

    protected String metricTaskName() {
        return streamKey().replace(':', '_');
    }

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected boolean shouldSkip(T payload) {
        return false;
    }

    protected abstract void markProcessing(T payload);

    /**
     * 尝试领取任务。默认保持原有消费者的状态更新语义。
     */
    protected boolean tryMarkProcessing(T payload) {
        markProcessing(payload);
        return true;
    }

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);

    protected abstract boolean retryMessage(T payload, int retryCount);
}
