package com.plantogether.task.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.plantogether.common.event.TaskAssignedEvent;
import com.plantogether.common.event.TaskDeadlineReminderEvent;
import com.plantogether.task.config.RabbitConfig;
import com.plantogether.task.event.publisher.TaskEventPublisher;
import com.plantogether.task.event.publisher.TaskEventPublisher.TaskAssignedInternalEvent;
import com.plantogether.task.event.publisher.TaskEventPublisher.TaskDeadlineReminderInternalEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class TaskEventPublisherTest {

  @Mock private RabbitTemplate rabbitTemplate;

  private TaskEventPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new TaskEventPublisher(rabbitTemplate, new SimpleMeterRegistry());
  }

  @Test
  void afterCommit_sendsTaskAssignedEvent_withCorrectRoutingKey() {
    UUID taskId = UUID.randomUUID();
    UUID tripId = UUID.randomUUID();
    String assigneeMemberId = UUID.randomUUID().toString();
    Instant now = Instant.now();

    TaskAssignedInternalEvent internal =
        new TaskAssignedInternalEvent(taskId, tripId, assigneeMemberId, "Book flights", null, now);

    publisher.publishTaskAssigned(internal);

    ArgumentCaptor<Object> messageCaptor = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);

    verify(rabbitTemplate)
        .convertAndSend(
            exchangeCaptor.capture(), routingKeyCaptor.capture(), messageCaptor.capture());

    assertThat(exchangeCaptor.getValue()).isEqualTo(RabbitConfig.EXCHANGE);
    assertThat(routingKeyCaptor.getValue()).isEqualTo(RabbitConfig.ROUTING_KEY_TASK_ASSIGNED);

    TaskAssignedEvent event = (TaskAssignedEvent) messageCaptor.getValue();
    assertThat(event.getTaskId()).isEqualTo(taskId);
    assertThat(event.getTripId()).isEqualTo(tripId);
    assertThat(event.getAssigneeMemberId()).isEqualTo(assigneeMemberId);
    assertThat(event.getAssigneeMemberId()).isInstanceOf(String.class);
    assertThat(event.getTitle()).isEqualTo("Book flights");
    assertThat(event.getAssignedAt()).isEqualTo(now);
  }

  @Test
  void afterCommit_sendsTaskDeadlineReminderEvent_withCorrectRoutingKey() {
    UUID taskId = UUID.randomUUID();
    UUID tripId = UUID.randomUUID();
    String assigneeMemberId = UUID.randomUUID().toString();
    Instant deadline = Instant.now().plusSeconds(3600);
    Instant reminderAt = Instant.now();

    TaskDeadlineReminderInternalEvent internal =
        new TaskDeadlineReminderInternalEvent(
            taskId, tripId, assigneeMemberId, "Pack bags", deadline, reminderAt);

    publisher.publishTaskDeadlineReminder(internal);

    ArgumentCaptor<Object> messageCaptor = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);

    verify(rabbitTemplate)
        .convertAndSend(
            exchangeCaptor.capture(), routingKeyCaptor.capture(), messageCaptor.capture());

    assertThat(exchangeCaptor.getValue()).isEqualTo(RabbitConfig.EXCHANGE);
    assertThat(routingKeyCaptor.getValue())
        .isEqualTo(RabbitConfig.ROUTING_KEY_TASK_DEADLINE_REMINDER);

    TaskDeadlineReminderEvent event = (TaskDeadlineReminderEvent) messageCaptor.getValue();
    assertThat(event.getTaskId()).isEqualTo(taskId);
    assertThat(event.getTripId()).isEqualTo(tripId);
    assertThat(event.getAssigneeMemberId()).isEqualTo(assigneeMemberId);
    assertThat(event.getAssigneeMemberId()).isInstanceOf(String.class);
    assertThat(event.getTitle()).isEqualTo("Pack bags");
    assertThat(event.getDeadline()).isEqualTo(deadline);
    assertThat(event.getReminderAt()).isEqualTo(reminderAt);
  }

  @Test
  void afterCommit_deadlineReminder_amqpExceptionSwallowed_doesNotPropagate() {
    doThrow(new org.springframework.amqp.AmqpException("broker down"))
        .when(rabbitTemplate)
        .convertAndSend(any(String.class), any(String.class), any(Object.class));

    TaskDeadlineReminderInternalEvent internal =
        new TaskDeadlineReminderInternalEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "Test",
            Instant.now(),
            Instant.now());

    org.assertj.core.api.Assertions.assertThatNoException()
        .isThrownBy(() -> publisher.publishTaskDeadlineReminder(internal));
  }
}
