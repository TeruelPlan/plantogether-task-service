package com.plantogether.task.event.publisher;

import com.plantogether.common.event.TaskAssignedEvent;
import com.plantogether.task.config.RabbitConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class TaskEventPublisher {

  private final RabbitTemplate rabbitTemplate;
  private final Counter publishFailures;

  public TaskEventPublisher(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
    this.rabbitTemplate = rabbitTemplate;
    this.publishFailures =
        Counter.builder("task_event_publish_failures_total")
            .description("Number of task event publish failures (post-commit, broker-side)")
            .tag("service", "task-service")
            .register(meterRegistry);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void publishTaskAssigned(TaskAssignedInternalEvent internal) {
    TaskAssignedEvent event =
        TaskAssignedEvent.builder()
            .taskId(internal.taskId())
            .tripId(internal.tripId())
            .assigneeMemberId(internal.assigneeMemberId())
            .title(internal.title())
            .deadline(internal.deadline())
            .assignedAt(internal.assignedAt())
            .build();
    publish(RabbitConfig.ROUTING_KEY_TASK_ASSIGNED, event, internal.taskId(), internal.tripId());
  }

  private void publish(String routingKey, Object event, UUID taskId, UUID tripId) {
    try {
      rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, event);
    } catch (AmqpException ex) {
      publishFailures.increment();
      log.warn(
          "Failed to publish {} (taskId={}, tripId={}): {}",
          routingKey,
          taskId,
          tripId,
          ex.getMessage(),
          ex);
    }
  }

  public record TaskAssignedInternalEvent(
      UUID taskId,
      UUID tripId,
      String assigneeMemberId,
      String title,
      Instant deadline,
      Instant assignedAt) {}
}
