package com.plantogether.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

  @Mock private TaskRepository taskRepository;
  @Mock private TripClient tripClient;
  @Mock private ApplicationEventPublisher eventPublisher;

  private TaskService service;

  private static final UUID TRIP_ID = UUID.randomUUID();
  private static final String DEVICE_ID = UUID.randomUUID().toString();
  private static final UUID ASSIGNEE_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new TaskService(taskRepository, tripClient, eventPublisher);
  }

  private Task savedTask(CreateTaskRequest req) {
    return Task.builder()
        .id(UUID.randomUUID())
        .tripId(TRIP_ID)
        .title(req.getTitle())
        .description(req.getDescription())
        .assigneeId(req.getAssigneeId())
        .status(TaskStatus.TODO)
        .priority(req.getPriority() != null ? req.getPriority() : TaskPriority.MEDIUM)
        .deadline(req.getDeadline())
        .createdBy(UUID.fromString(DEVICE_ID))
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }

  @Test
  void create_member_noAssignee_savesNoEvent() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    CreateTaskRequest req = CreateTaskRequest.builder().title("Buy tickets").build();
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> savedTask(req));

    TaskResponse response = service.createTask(TRIP_ID, DEVICE_ID, req);

    verify(taskRepository).save(any(Task.class));
    verify(eventPublisher, never()).publishEvent(any());
    assertThat(response.getTitle()).isEqualTo("Buy tickets");
  }

  @Test
  void create_member_withValidAssignee_savesAndPublishesEvent() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(tripClient.isMember(TRIP_ID.toString(), ASSIGNEE_ID.toString())).thenReturn(true);

    CreateTaskRequest req =
        CreateTaskRequest.builder().title("Pack bags").assigneeId(ASSIGNEE_ID).build();
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> savedTask(req));

    service.createTask(TRIP_ID, DEVICE_ID, req);

    verify(taskRepository).save(any(Task.class));
    ArgumentCaptor<TaskAssignedInternalEvent> captor =
        ArgumentCaptor.forClass(TaskAssignedInternalEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().assigneeMemberId()).isEqualTo(ASSIGNEE_ID.toString());
  }

  @Test
  void create_member_withNonMemberAssignee_throws400() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(tripClient.isMember(TRIP_ID.toString(), ASSIGNEE_ID.toString())).thenReturn(false);

    CreateTaskRequest req =
        CreateTaskRequest.builder().title("Bring food").assigneeId(ASSIGNEE_ID).build();

    assertThatThrownBy(() -> service.createTask(TRIP_ID, DEVICE_ID, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a member");

    verify(taskRepository, never()).save(any());
  }

  @Test
  void create_defaultsPriorityToMedium_andStatusToTodo() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    CreateTaskRequest req = CreateTaskRequest.builder().title("Arrive early").build();
    ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
    when(taskRepository.save(captor.capture())).thenAnswer(inv -> savedTask(req));

    service.createTask(TRIP_ID, DEVICE_ID, req);

    Task saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo(TaskStatus.TODO);
    assertThat(saved.getPriority()).isEqualTo(TaskPriority.MEDIUM);
  }

  @Test
  void create_nonMember_throwsAccessDenied() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(false);
    CreateTaskRequest req = CreateTaskRequest.builder().title("Reserve hotel").build();

    assertThatThrownBy(() -> service.createTask(TRIP_ID, DEVICE_ID, req))
        .isInstanceOf(AccessDeniedException.class);

    verify(taskRepository, never()).save(any());
  }

  @Test
  void list_member_returnsTopLevelTasksOrderedByCreatedAtDesc() {
    Instant t1 = Instant.parse("2026-05-01T10:00:00Z");
    Instant t2 = Instant.parse("2026-05-02T10:00:00Z");
    Task older =
        Task.builder()
            .id(UUID.randomUUID())
            .tripId(TRIP_ID)
            .title("Older task")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(t1)
            .updatedAt(t1)
            .build();
    Task newer =
        Task.builder()
            .id(UUID.randomUUID())
            .tripId(TRIP_ID)
            .title("Newer task")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.HIGH)
            .createdBy(UUID.randomUUID())
            .createdAt(t2)
            .updatedAt(t2)
            .build();

    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findByTripIdAndParentTaskIdIsNullOrderByCreatedAtDesc(TRIP_ID))
        .thenReturn(List.of(newer, older));

    List<TaskResponse> result = service.listTasks(TRIP_ID, DEVICE_ID);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getTitle()).isEqualTo("Newer task");
    assertThat(result.get(1).getTitle()).isEqualTo("Older task");
    verify(taskRepository).findByTripIdAndParentTaskIdIsNullOrderByCreatedAtDesc(TRIP_ID);
  }

  @Test
  void list_nonMember_throwsAccessDenied() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.listTasks(TRIP_ID, DEVICE_ID))
        .isInstanceOf(AccessDeniedException.class);

    verify(taskRepository, never()).findByTripIdAndParentTaskIdIsNullOrderByCreatedAtDesc(any());
  }
}
