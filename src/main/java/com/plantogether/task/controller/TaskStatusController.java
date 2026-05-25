package com.plantogether.task.controller;

import com.plantogether.task.dto.TaskResponse;
import com.plantogether.task.dto.UpdateTaskStatusRequest;
import com.plantogether.task.service.TaskService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskStatusController {

  private final TaskService taskService;

  @PatchMapping("/{taskId}/status")
  public TaskResponse updateStatus(
      Authentication auth,
      @PathVariable UUID taskId,
      @Valid @RequestBody UpdateTaskStatusRequest req) {
    return taskService.updateStatus(taskId, auth.getName(), req.getStatus());
  }
}
