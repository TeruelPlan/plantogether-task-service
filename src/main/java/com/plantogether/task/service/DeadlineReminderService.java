package com.plantogether.task.service;

import com.plantogether.task.domain.Task;
import com.plantogether.task.event.publisher.TaskEventPublisher.TaskDeadlineReminderInternalEvent;
import com.plantogether.task.repository.TaskRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeadlineReminderService {

  private final TaskRepository taskRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean remindOne(Task task, Instant now) {
    int claimed = taskRepository.claimForReminder(task.getId(), now);
    if (claimed == 0) {
      return false;
    }
    eventPublisher.publishEvent(
        new TaskDeadlineReminderInternalEvent(
            task.getId(),
            task.getTripId(),
            task.getAssigneeId().toString(),
            task.getTitle(),
            task.getDeadline(),
            now));
    return true;
  }
}
