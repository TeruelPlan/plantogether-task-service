package com.plantogether.task.service;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.task.domain.Task;
import com.plantogether.task.domain.TaskPriority;
import com.plantogether.task.domain.TaskStatus;
import com.plantogether.task.dto.CreateTaskRequest;
import com.plantogether.task.dto.TaskResponse;
import com.plantogether.task.event.publisher.TaskEventPublisher.TaskAssignedInternalEvent;
import com.plantogether.task.repository.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskService {

  private final TaskRepository taskRepository;
  private final TripClient tripClient;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public TaskResponse createTask(UUID tripId, String deviceId, CreateTaskRequest req) {
    if (!tripClient.isMember(tripId.toString(), deviceId)) {
      throw new AccessDeniedException("Not a member of this trip");
    }

    if (req.getAssigneeId() != null
        && !tripClient.isMember(tripId.toString(), req.getAssigneeId().toString())) {
      throw new IllegalArgumentException("Assignee is not a member of this trip");
    }

    Task task =
        Task.builder()
            .tripId(tripId)
            .parentTaskId(null)
            .title(req.getTitle())
            .description(req.getDescription())
            .assigneeId(req.getAssigneeId())
            .status(TaskStatus.TODO)
            .priority(req.getPriority() != null ? req.getPriority() : TaskPriority.MEDIUM)
            .deadline(req.getDeadline())
            .createdBy(UUID.fromString(deviceId))
            .build();

    Task saved = taskRepository.save(task);

    if (req.getAssigneeId() != null) {
      eventPublisher.publishEvent(
          new TaskAssignedInternalEvent(
              saved.getId(),
              tripId,
              req.getAssigneeId().toString(),
              saved.getTitle(),
              saved.getDeadline(),
              Instant.now()));
    }

    return TaskResponse.from(saved);
  }

  @Transactional(readOnly = true)
  public List<TaskResponse> listTasks(UUID tripId, String deviceId) {
    if (!tripClient.isMember(tripId.toString(), deviceId)) {
      throw new AccessDeniedException("Not a member of this trip");
    }

    return taskRepository.findByTripIdAndParentTaskIdIsNullOrderByCreatedAtDesc(tripId).stream()
        .map(TaskResponse::from)
        .toList();
  }
}
