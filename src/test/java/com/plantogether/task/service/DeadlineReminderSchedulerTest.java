package com.plantogether.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.plantogether.task.domain.Task;
import com.plantogether.task.domain.TaskStatus;
import com.plantogether.task.repository.TaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeadlineReminderSchedulerTest {

  @Mock private TaskRepository taskRepository;
  @Mock private DeadlineReminderService reminderService;

  private DeadlineReminderScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler =
        new DeadlineReminderScheduler(
            taskRepository, reminderService, new SimpleMeterRegistry(), 24);
  }

  private Task taskWithDeadline(Instant deadline) {
    return Task.builder()
        .id(UUID.randomUUID())
        .tripId(UUID.randomUUID())
        .title("Book hotel")
        .assigneeId(UUID.randomUUID())
        .status(TaskStatus.TODO)
        .deadline(deadline)
        .build();
  }

  @Test
  void scan_dueTaskNotReminded_delegatesToReminderService() {
    Task task = taskWithDeadline(Instant.now().plus(12, ChronoUnit.HOURS));
    when(taskRepository.findDueForReminder(any(), any())).thenReturn(List.of(task));

    scheduler.scanDueDeadlines();

    verify(reminderService).remindOne(eq(task), any(Instant.class));
  }

  @Test
  void scan_noDueTasks_reminderServiceNeverCalled() {
    when(taskRepository.findDueForReminder(any(), any())).thenReturn(List.of());

    scheduler.scanDueDeadlines();

    verifyNoInteractions(reminderService);
  }

  @Test
  void scan_oneTaskThrows_othersStillProcessed() {
    Task task1 = taskWithDeadline(Instant.now().plus(2, ChronoUnit.HOURS));
    Task task2 = taskWithDeadline(Instant.now().plus(6, ChronoUnit.HOURS));
    Task task3 = taskWithDeadline(Instant.now().plus(10, ChronoUnit.HOURS));
    when(taskRepository.findDueForReminder(any(), any())).thenReturn(List.of(task1, task2, task3));
    doThrow(new RuntimeException("broker down")).when(reminderService).remindOne(eq(task2), any());

    scheduler.scanDueDeadlines();

    verify(reminderService).remindOne(eq(task1), any());
    verify(reminderService).remindOne(eq(task2), any());
    verify(reminderService).remindOne(eq(task3), any());
  }

  @Test
  void scan_windowBounds_passesNowAndNowPlus24h() {
    when(taskRepository.findDueForReminder(any(), any())).thenReturn(List.of());

    Instant before = Instant.now();
    scheduler.scanDueDeadlines();
    Instant after = Instant.now();

    ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> windowCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(taskRepository).findDueForReminder(nowCaptor.capture(), windowCaptor.capture());

    Instant capturedNow = nowCaptor.getValue();
    Instant capturedWindow = windowCaptor.getValue();
    assertThat(capturedNow).isBetween(before, after);
    assertThat(capturedWindow)
        .isBetween(before.plus(24, ChronoUnit.HOURS), after.plus(24, ChronoUnit.HOURS));
  }
}
