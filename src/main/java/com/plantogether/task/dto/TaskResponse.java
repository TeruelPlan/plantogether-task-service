package com.plantogether.task.dto;

import com.plantogether.task.domain.Task;
import com.plantogether.task.domain.TaskPriority;
import com.plantogether.task.domain.TaskStatus;
import java.time.Instant;
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
}
