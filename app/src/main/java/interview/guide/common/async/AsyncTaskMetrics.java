package interview.guide.common.async;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis Stream 异步任务的统一 Micrometer 指标入口。
 */
@Component
public class AsyncTaskMetrics {

  private static final String METRIC_ENQUEUE = "app.async.task.enqueue";
  private static final String METRIC_QUEUE_WAIT = "app.async.task.queue.wait";
  private static final String METRIC_PROCESSING = "app.async.task.processing";
  private static final String METRIC_EVENTS = "app.async.task.events";
  private static final String METRIC_REPLAY = "app.async.task.replay";

  private final MeterRegistry meterRegistry;

  public AsyncTaskMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void recordEnqueue(String task, String outcome, long elapsedNanos) {
    Tags tags = Tags.of("task", task, "outcome", outcome);
    meterRegistry.counter(METRIC_ENQUEUE, tags).increment();
    meterRegistry.timer(METRIC_ENQUEUE + ".latency", tags)
        .record(elapsedNanos, TimeUnit.NANOSECONDS);
  }

  public void recordQueueWait(String task, long queueWaitMillis) {
    if (queueWaitMillis < 0) {
      return;
    }
    meterRegistry.timer(METRIC_QUEUE_WAIT, "task", task)
        .record(queueWaitMillis, TimeUnit.MILLISECONDS);
  }

  public void recordProcessing(String task, String outcome, long elapsedNanos) {
    meterRegistry.timer(METRIC_PROCESSING, "task", task, "outcome", outcome)
        .record(elapsedNanos, TimeUnit.NANOSECONDS);
  }

  public void recordEvent(String task, String event) {
    meterRegistry.counter(METRIC_EVENTS, "task", task, "event", event).increment();
  }

  public void recordReplay(String task, String outcome) {
    meterRegistry.counter(METRIC_REPLAY, "task", task, "outcome", outcome).increment();
  }
}
