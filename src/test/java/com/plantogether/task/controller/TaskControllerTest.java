package com.plantogether.task.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.security.SecurityAutoConfiguration;
import com.plantogether.task.domain.TaskPriority;
import com.plantogether.task.domain.TaskStatus;
import com.plantogether.task.dto.TaskResponse;
import com.plantogether.task.exception.GlobalExceptionHandler;
import com.plantogether.task.service.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskController.class)
@Import({SecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class TaskControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TaskService taskService;
  @MockitoBean private TripClient tripClient;

  private final UUID deviceId = UUID.randomUUID();
  private final UUID tripId = UUID.randomUUID();

  private TaskResponse sampleResponse() {
    return TaskResponse.builder()
        .id(UUID.randomUUID())
        .tripId(tripId)
        .title("Pack bags")
        .status(TaskStatus.TODO)
        .priority(TaskPriority.MEDIUM)
        .createdBy(deviceId)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }

  private String validBody() {
    return """
    {
      "title": "Pack bags"
    }
    """;
  }

  @Test
  void create_returns201_withValidBody() throws Exception {
    when(taskService.createTask(eq(tripId), eq(deviceId.toString()), any()))
        .thenReturn(sampleResponse());

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(validBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title").value("Pack bags"))
        .andExpect(jsonPath("$.status").value("TODO"))
        .andExpect(jsonPath("$.priority").value("MEDIUM"));
  }

  @Test
  void create_returns400_withBlankTitle() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "title": "" }
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void create_returns400_withWhitespaceOnlyTitle() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "title": "   " }
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void create_returns400_withMalformedJson() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content("{invalid json"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void create_returns400_withNonMemberAssignee() throws Exception {
    when(taskService.createTask(eq(tripId), eq(deviceId.toString()), any()))
        .thenThrow(new IllegalArgumentException("Assignee is not a member of this trip"));

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "title": "Task", "assigneeId": "%s" }
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void create_returns403_forNonMember() throws Exception {
    when(taskService.createTask(eq(tripId), eq(deviceId.toString()), any()))
        .thenThrow(new AccessDeniedException("Not a member"));

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(validBody()))
        .andExpect(status().isForbidden());
  }

  @Test
  void list_returns200_withTasks() throws Exception {
    when(taskService.listTasks(eq(tripId), eq(deviceId.toString())))
        .thenReturn(List.of(sampleResponse()));

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/tasks", tripId).header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].title").value("Pack bags"));
  }

  @Test
  void list_returns200_emptyList_whenNoTasks() throws Exception {
    when(taskService.listTasks(eq(tripId), eq(deviceId.toString()))).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/tasks", tripId).header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void list_returns403_forNonMember() throws Exception {
    when(taskService.listTasks(eq(tripId), eq(deviceId.toString())))
        .thenThrow(new AccessDeniedException("Not a member"));

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/tasks", tripId).header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isForbidden());
  }

  // ─── Subtask controller tests ─────────────────────────────────────────────

  @Test
  void createSubtask_returns201_withParentTaskIdInBody() throws Exception {
    UUID parentId = UUID.randomUUID();
    TaskResponse response =
        TaskResponse.builder()
            .id(UUID.randomUUID())
            .tripId(tripId)
            .parentTaskId(parentId)
            .title("Pack bags")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(deviceId)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(taskService.createTask(eq(tripId), eq(deviceId.toString()), any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "title": "Pack bags", "parentTaskId": "%s" }
                    """
                        .formatted(parentId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.parentTaskId").value(parentId.toString()));
  }

  @Test
  void createSubtask_returns400_whenParentIsSubtask() throws Exception {
    when(taskService.createTask(eq(tripId), eq(deviceId.toString()), any()))
        .thenThrow(new IllegalArgumentException("Cannot nest a subtask under another subtask"));

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "title": "Deep sub", "parentTaskId": "%s" }
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createSubtask_returns400_whenParentInOtherTrip() throws Exception {
    when(taskService.createTask(eq(tripId), eq(deviceId.toString()), any()))
        .thenThrow(new IllegalArgumentException("Parent task belongs to a different trip"));

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "title": "Cross trip", "parentTaskId": "%s" }
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createSubtask_returns400_withBlankTitle() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "title": "", "parentTaskId": "%s" }
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void list_returns200_withNestedSubtasksAndSummary() throws Exception {
    UUID parentId = UUID.randomUUID();
    UUID childId = UUID.randomUUID();

    TaskResponse child =
        TaskResponse.builder()
            .id(childId)
            .tripId(tripId)
            .parentTaskId(parentId)
            .title("Subtask 1")
            .status(TaskStatus.DONE)
            .priority(TaskPriority.MEDIUM)
            .createdBy(deviceId)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    TaskResponse parent =
        TaskResponse.builder()
            .id(parentId)
            .tripId(tripId)
            .title("Parent task")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(deviceId)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .subtasks(List.of(child))
            .subtaskSummary(new TaskResponse.SubtaskSummary(1, 1))
            .build();

    when(taskService.listTasks(eq(tripId), eq(deviceId.toString()))).thenReturn(List.of(parent));

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/tasks", tripId).header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].subtasks").isArray())
        .andExpect(jsonPath("$[0].subtasks.length()").value(1))
        .andExpect(jsonPath("$[0].subtasks[0].title").value("Subtask 1"))
        .andExpect(jsonPath("$[0].subtaskSummary.total").value(1))
        .andExpect(jsonPath("$[0].subtaskSummary.done").value(1));
  }

  @Test
  void createSubtask_returns403_forNonMember() throws Exception {
    when(taskService.createTask(eq(tripId), eq(deviceId.toString()), any()))
        .thenThrow(new AccessDeniedException("Not a member"));

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(
                    """
                    { "title": "Sub task", "parentTaskId": "%s" }
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isForbidden());
  }
}
