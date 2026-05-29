package com.plantogether.task.dto;

import com.plantogether.task.domain.Task;
import com.plantogether.task.domain.TaskPriority;
import com.plantogether.task.domain.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskResponse {

  private UUID id;
  private UUID tripId;
  private UUID parentTaskId;
  private String title;
  private String description;
  private UUID assigneeId;
  private TaskStatus status;
  private TaskPriority priority;
  private Instant deadline;
  private UUID createdBy;
  private Instant completedAt;
  private Instant createdAt;
  private Instant updatedAt;

  /** Populated only on top-level list items that have at least one subtask. Null otherwise. */
  private List<TaskResponse> subtasks;

  /** Populated only on top-level list items that have at least one subtask. Null otherwise. */
  private SubtaskSummary subtaskSummary;

  /** Aggregated subtask counts for a parent task. */
  public record SubtaskSummary(int total, int done) {}

  /** Flat factory — used for create responses and subtask rows. */
  public static TaskResponse from(Task entity) {
    return TaskResponse.builder()
        .id(entity.getId())
        .tripId(entity.getTripId())
        .parentTaskId(entity.getParentTaskId())
        .title(entity.getTitle())
        .description(entity.getDescription())
        .assigneeId(entity.getAssigneeId())
        .status(entity.getStatus())
        .priority(entity.getPriority())
        .deadline(entity.getDeadline())
        .createdBy(entity.getCreatedBy())
        .completedAt(entity.getCompletedAt())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }

  /** Nested factory — used for top-level list items that have subtasks. */
  public static TaskResponse fromWithSubtasks(
      Task entity, List<TaskResponse> subtasks, SubtaskSummary subtaskSummary) {
    TaskResponse response = from(entity);
    response.setSubtasks(subtasks);
    response.setSubtaskSummary(subtaskSummary);
    return response;
  }
}
