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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

  // ─── Subtask creation tests ───────────────────────────────────────────────

  @Test
  void createSubtask_validParent_setsParentTaskId() {
    UUID parentId = UUID.randomUUID();
    Task parent =
        Task.builder()
            .id(parentId)
            .tripId(TRIP_ID)
            .title("Parent")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findById(parentId)).thenReturn(Optional.of(parent));

    CreateTaskRequest req =
        CreateTaskRequest.builder().title("Sub-task").parentTaskId(parentId).build();
    ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
    when(taskRepository.save(captor.capture()))
        .thenAnswer(inv -> savedTaskWithParent(req, parentId));

    TaskResponse response = service.createTask(TRIP_ID, DEVICE_ID, req);

    Task saved = captor.getValue();
    assertThat(saved.getParentTaskId()).isEqualTo(parentId);
    assertThat(saved.getStatus()).isEqualTo(TaskStatus.TODO);
    assertThat(response.getParentTaskId()).isEqualTo(parentId);
  }

  @Test
  void createSubtask_withAssignee_publishesTaskAssignedEvent() {
    UUID parentId = UUID.randomUUID();
    Task parent =
        Task.builder()
            .id(parentId)
            .tripId(TRIP_ID)
            .title("Parent")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(tripClient.isMember(TRIP_ID.toString(), ASSIGNEE_ID.toString())).thenReturn(true);
    when(taskRepository.findById(parentId)).thenReturn(Optional.of(parent));

    CreateTaskRequest req =
        CreateTaskRequest.builder()
            .title("Sub with assignee")
            .parentTaskId(parentId)
            .assigneeId(ASSIGNEE_ID)
            .build();
    when(taskRepository.save(any(Task.class)))
        .thenAnswer(inv -> savedTaskWithParent(req, parentId));

    service.createTask(TRIP_ID, DEVICE_ID, req);

    ArgumentCaptor<TaskAssignedInternalEvent> captor =
        ArgumentCaptor.forClass(TaskAssignedInternalEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().assigneeMemberId()).isEqualTo(ASSIGNEE_ID.toString());
  }

  @Test
  void createSubtask_parentNotFound_throws400() {
    UUID missingParentId = UUID.randomUUID();
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findById(missingParentId)).thenReturn(Optional.empty());

    CreateTaskRequest req =
        CreateTaskRequest.builder().title("Orphan").parentTaskId(missingParentId).build();

    assertThatThrownBy(() -> service.createTask(TRIP_ID, DEVICE_ID, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Parent task not found");

    verify(taskRepository, never()).save(any());
  }

  @Test
  void createSubtask_parentInDifferentTrip_throws400() {
    UUID parentId = UUID.randomUUID();
    UUID otherTrip = UUID.randomUUID();
    Task parent =
        Task.builder()
            .id(parentId)
            .tripId(otherTrip)
            .title("Foreign parent")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findById(parentId)).thenReturn(Optional.of(parent));

    CreateTaskRequest req =
        CreateTaskRequest.builder().title("Cross-trip sub").parentTaskId(parentId).build();

    assertThatThrownBy(() -> service.createTask(TRIP_ID, DEVICE_ID, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("different trip");

    verify(taskRepository, never()).save(any());
  }

  @Test
  void createSubtask_parentIsItselfSubtask_throws400() {
    UUID grandParentId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    Task parent =
        Task.builder()
            .id(parentId)
            .tripId(TRIP_ID)
            .parentTaskId(grandParentId)
            .title("Already a subtask")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findById(parentId)).thenReturn(Optional.of(parent));

    CreateTaskRequest req =
        CreateTaskRequest.builder().title("Deep sub").parentTaskId(parentId).build();

    assertThatThrownBy(() -> service.createTask(TRIP_ID, DEVICE_ID, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot nest a subtask under another subtask");

    verify(taskRepository, never()).save(any());
  }

  // ─── Nested list assembly tests ───────────────────────────────────────────

  @Test
  void listTasks_nestsSubtasksUnderParents_withSummary() {
    Instant now = Instant.now();
    UUID parentAId = UUID.randomUUID();
    UUID parentBId = UUID.randomUUID();

    Task parentA =
        Task.builder()
            .id(parentAId)
            .tripId(TRIP_ID)
            .title("Parent A")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(now)
            .updatedAt(now)
            .build();
    Task parentB =
        Task.builder()
            .id(parentBId)
            .tripId(TRIP_ID)
            .title("Parent B")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.LOW)
            .createdBy(UUID.randomUUID())
            .createdAt(now)
            .updatedAt(now)
            .build();

    Task child1 =
        Task.builder()
            .id(UUID.randomUUID())
            .tripId(TRIP_ID)
            .parentTaskId(parentAId)
            .title("Child 1")
            .status(TaskStatus.DONE)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(now)
            .updatedAt(now)
            .build();
    Task child2 =
        Task.builder()
            .id(UUID.randomUUID())
            .tripId(TRIP_ID)
            .parentTaskId(parentAId)
            .title("Child 2")
            .status(TaskStatus.DONE)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(now)
            .updatedAt(now)
            .build();
    Task child3 =
        Task.builder()
            .id(UUID.randomUUID())
            .tripId(TRIP_ID)
            .parentTaskId(parentAId)
            .title("Child 3")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(now)
            .updatedAt(now)
            .build();

    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findByTripIdAndParentTaskIdIsNullOrderByCreatedAtDesc(TRIP_ID))
        .thenReturn(List.of(parentA, parentB));
    when(taskRepository.findByParentTaskIdInOrderByCreatedAtAsc(any()))
        .thenReturn(List.of(child1, child2, child3));

    List<TaskResponse> result = service.listTasks(TRIP_ID, DEVICE_ID);

    assertThat(result).hasSize(2);

    TaskResponse responseA = result.get(0);
    assertThat(responseA.getSubtasks()).hasSize(3);
    assertThat(responseA.getSubtaskSummary()).isNotNull();
    assertThat(responseA.getSubtaskSummary().total()).isEqualTo(3);
    assertThat(responseA.getSubtaskSummary().done()).isEqualTo(2);

    TaskResponse responseB = result.get(1);
    assertThat(responseB.getSubtasks()).isNull();
    assertThat(responseB.getSubtaskSummary()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void listTasks_usesBatchChildQuery_notPerParent() {
    Instant now = Instant.now();
    UUID parentId = UUID.randomUUID();
    Task parent =
        Task.builder()
            .id(parentId)
            .tripId(TRIP_ID)
            .title("Parent")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(now)
            .updatedAt(now)
            .build();

    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findByTripIdAndParentTaskIdIsNullOrderByCreatedAtDesc(TRIP_ID))
        .thenReturn(List.of(parent));
    when(taskRepository.findByParentTaskIdInOrderByCreatedAtAsc(any(Collection.class)))
        .thenReturn(List.of());

    service.listTasks(TRIP_ID, DEVICE_ID);

    verify(taskRepository, times(1)).findByParentTaskIdInOrderByCreatedAtAsc(any(Collection.class));
    verify(taskRepository, never()).findById(any());
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────

  private Task savedTaskWithParent(CreateTaskRequest req, UUID parentTaskId) {
    return Task.builder()
        .id(UUID.randomUUID())
        .tripId(TRIP_ID)
        .parentTaskId(parentTaskId)
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
}
