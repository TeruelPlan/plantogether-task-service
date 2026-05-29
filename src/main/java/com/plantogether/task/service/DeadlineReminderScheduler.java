package com.plantogether.task.service;

import com.plantogether.task.domain.Task;
import com.plantogether.task.repository.TaskRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeadlineReminderScheduler {

  private final TaskRepository taskRepository;
  private final DeadlineReminderService reminderService;
  private final Counter reminderFailures;
  private final int windowHours;

  public DeadlineReminderScheduler(
      TaskRepository taskRepository,
      DeadlineReminderService reminderService,
      MeterRegistry meterRegistry,
      @Value("${task.reminder.window-hours:24}") int windowHours) {
    this.taskRepository = taskRepository;
    this.reminderService = reminderService;
    this.windowHours = windowHours;
    this.reminderFailures =
        Counter.builder("task_deadline_reminder_failures_total")
            .description("Number of deadline reminder processing failures")
            .tag("service", "task-service")
            .register(meterRegistry);
  }

  @Scheduled(cron = "${task.reminder.cron:0 0 * * * *}", zone = "UTC")
  public void scanDueDeadlines() {
    Instant now = Instant.now();
    Instant windowEnd = now.plus(Duration.ofHours(windowHours));
    List<Task> due = taskRepository.findDueForReminder(now, windowEnd);
    log.debug("Deadline reminder scan: {} candidate task(s) found", due.size());
    for (Task task : due) {
      try {
        reminderService.remindOne(task, now);
      } catch (Exception ex) {
        reminderFailures.increment();
        log.warn("Deadline reminder failed for task {}: {}", task.getId(), ex.getMessage(), ex);
      }
    }
  }
}
