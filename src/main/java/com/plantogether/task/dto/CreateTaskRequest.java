package com.plantogether.task.dto;

import com.plantogether.task.domain.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
public class CreateTaskRequest {

  @NotBlank
  @Size(max = 255)
  private String title;

  @Size(max = 4000)
  private String description;

  private UUID assigneeId;

  private TaskPriority priority;

  private Instant deadline;
}
