package com.plantogether.task.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.exception.ResourceNotFoundException;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.security.SecurityAutoConfiguration;
import com.plantogether.task.domain.TaskPriority;
import com.plantogether.task.domain.TaskStatus;
import com.plantogether.task.dto.TaskResponse;
import com.plantogether.task.exception.GlobalExceptionHandler;
import com.plantogether.task.service.TaskService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskStatusController.class)
@Import({SecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class TaskStatusControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TaskService taskService;
  @MockitoBean private TripClient tripClient;

  private final UUID deviceId = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();

  private TaskResponse doneTaskResponse() {
    return TaskResponse.builder()
        .id(taskId)
        .tripId(UUID.randomUUID())
        .title("Pack bags")
        .status(TaskStatus.DONE)
        .priority(TaskPriority.MEDIUM)
        .completedAt(Instant.now())
        .createdBy(deviceId)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }

  @Test
  void patchStatus_returns200_withUpdatedTaskResponse() throws Exception {
    when(taskService.updateStatus(eq(taskId), eq(deviceId.toString()), eq(TaskStatus.DONE)))
        .thenReturn(doneTaskResponse());

    mockMvc
        .perform(
            patch("/api/v1/tasks/{taskId}/status", taskId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "status": "DONE" }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.completedAt").isNotEmpty());
  }

  @Test
  void patchStatus_returns400_whenStatusMissing() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/tasks/{taskId}/status", taskId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void patchStatus_returns400_whenStatusUnknownEnum() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/tasks/{taskId}/status", taskId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "status": "INVALID" }
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void patchStatus_returns403_forNonMember() throws Exception {
    when(taskService.updateStatus(eq(taskId), eq(deviceId.toString()), any()))
        .thenThrow(new AccessDeniedException("Not a member of this trip"));

    mockMvc
        .perform(
            patch("/api/v1/tasks/{taskId}/status", taskId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "status": "DONE" }
                    """))
        .andExpect(status().isForbidden());
  }

  @Test
  void patchStatus_returns403_forMemberNeitherAssigneeNorOrganizer() throws Exception {
    when(taskService.updateStatus(eq(taskId), eq(deviceId.toString()), any()))
        .thenThrow(
            new AccessDeniedException(
                "Only the assignee or the trip organizer can change this task's status"));

    mockMvc
        .perform(
            patch("/api/v1/tasks/{taskId}/status", taskId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "status": "IN_PROGRESS" }
                    """))
        .andExpect(status().isForbidden());
  }

  @Test
  void patchStatus_returns404_whenTaskMissing() throws Exception {
    when(taskService.updateStatus(eq(taskId), eq(deviceId.toString()), any()))
        .thenThrow(new ResourceNotFoundException("Task not found"));

    mockMvc
        .perform(
            patch("/api/v1/tasks/{taskId}/status", taskId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "status": "DONE" }
                    """))
        .andExpect(status().isNotFound());
  }
}
