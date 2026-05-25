package com.plantogether.task.service;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.exception.ResourceNotFoundException;
import com.plantogether.common.grpc.Role;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.grpc.TripMembership;
import com.plantogether.task.domain.Task;
import com.plantogether.task.domain.TaskPriority;
import com.plantogether.task.domain.TaskStatus;
import com.plantogether.task.dto.CreateTaskRequest;
import com.plantogether.task.dto.TaskResponse;
import com.plantogether.task.dto.TaskResponse.SubtaskSummary;
import com.plantogether.task.event.publisher.TaskEventPublisher.TaskAssignedInternalEvent;
import com.plantogether.task.repository.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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

    UUID parentTaskId = null;
    if (req.getParentTaskId() != null) {
      Task parent =
          taskRepository
              .findById(req.getParentTaskId())
              .orElseThrow(() -> new IllegalArgumentException("Parent task not found"));

      if (!parent.getTripId().equals(tripId)) {
        throw new IllegalArgumentException("Parent task belongs to a different trip");
      }
      if (parent.getParentTaskId() != null) {
        throw new IllegalArgumentException("Cannot nest a subtask under another subtask");
      }
      parentTaskId = parent.getId();
    }

    Task task =
        Task.builder()
            .tripId(tripId)
            .parentTaskId(parentTaskId)
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

  @Transactional
  public TaskResponse updateStatus(UUID taskId, String deviceId, TaskStatus newStatus) {
    Task task =
        taskRepository
            .findById(taskId)
            .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

    TripMembership membership = tripClient.requireMembership(task.getTripId().toString(), deviceId);

    boolean isAssignee =
        task.getAssigneeId() != null && deviceId.equals(task.getAssigneeId().toString());
    boolean isOrganizer = membership.role() == Role.ORGANIZER;

    if (!isAssignee && !isOrganizer) {
      throw new AccessDeniedException(
          "Only the assignee or the trip organizer can change this task's status");
    }

    task.setStatus(newStatus);
    if (newStatus == TaskStatus.DONE) {
      task.setCompletedAt(Instant.now());
    } else {
      task.setCompletedAt(null);
    }

    Task saved = taskRepository.save(task);
    return TaskResponse.from(saved);
  }

  @Transactional(readOnly = true)
  public List<TaskResponse> listTasks(UUID tripId, String deviceId) {
    if (!tripClient.isMember(tripId.toString(), deviceId)) {
      throw new AccessDeniedException("Not a member of this trip");
    }

    List<Task> parents =
        taskRepository.findByTripIdAndParentTaskIdIsNullOrderByCreatedAtDesc(tripId);
    if (parents.isEmpty()) {
      return List.of();
    }

    List<Task> children =
        taskRepository.findByParentTaskIdInOrderByCreatedAtAsc(
            parents.stream().map(Task::getId).toList());

    Map<UUID, List<Task>> childrenByParent =
        children.stream().collect(Collectors.groupingBy(Task::getParentTaskId));

    return parents.stream()
        .map(
            parent -> {
              List<Task> subs = childrenByParent.getOrDefault(parent.getId(), List.of());
              if (subs.isEmpty()) {
                return TaskResponse.from(parent);
              }
              List<TaskResponse> subResponses = subs.stream().map(TaskResponse::from).toList();
              int done = (int) subs.stream().filter(s -> s.getStatus() == TaskStatus.DONE).count();
              SubtaskSummary summary = new SubtaskSummary(subs.size(), done);
              return TaskResponse.fromWithSubtasks(parent, subResponses, summary);
            })
        .toList();
  }
}
