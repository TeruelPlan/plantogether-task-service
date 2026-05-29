package com.plantogether.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
    when(taskRepository.findTopLevelFiltered(TRIP_ID, null, null))
        .thenReturn(List.of(newer, older));

    List<TaskResponse> result = service.listTasks(TRIP_ID, DEVICE_ID, null, null);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getTitle()).isEqualTo("Newer task");
    assertThat(result.get(1).getTitle()).isEqualTo("Older task");
    verify(taskRepository).findTopLevelFiltered(TRIP_ID, null, null);
  }

  @Test
  void list_nonMember_throwsAccessDenied() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.listTasks(TRIP_ID, DEVICE_ID, null, null))
        .isInstanceOf(AccessDeniedException.class);

    verify(taskRepository, never()).findTopLevelFiltered(any(), any(), any());
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
    when(taskRepository.findTopLevelFiltered(TRIP_ID, null, null))
        .thenReturn(List.of(parentA, parentB));
    when(taskRepository.findByParentTaskIdInOrderByCreatedAtAsc(any()))
        .thenReturn(List.of(child1, child2, child3));

    List<TaskResponse> result = service.listTasks(TRIP_ID, DEVICE_ID, null, null);

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
    when(taskRepository.findTopLevelFiltered(TRIP_ID, null, null)).thenReturn(List.of(parent));
    when(taskRepository.findByParentTaskIdInOrderByCreatedAtAsc(any(Collection.class)))
        .thenReturn(List.of());

    service.listTasks(TRIP_ID, DEVICE_ID, null, null);

    verify(taskRepository, times(1)).findByParentTaskIdInOrderByCreatedAtAsc(any(Collection.class));
    verify(taskRepository, never()).findById(any());
  }

  // ─── updateStatus tests ───────────────────────────────────────────────────

  @Test
  void updateStatus_toInProgress_setsStatus_completedAtStaysNull() {
    UUID taskId = UUID.randomUUID();
    Task task =
        Task.builder()
            .id(taskId)
            .tripId(TRIP_ID)
            .title("Buy tickets")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .assigneeId(UUID.fromString(DEVICE_ID))
            .createdBy(UUID.fromString(DEVICE_ID))
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, UUID.randomUUID().toString()));
    ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
    when(taskRepository.save(captor.capture())).thenAnswer(inv -> captor.getValue());

    TaskResponse response = service.updateStatus(taskId, DEVICE_ID, TaskStatus.IN_PROGRESS);

    assertThat(response.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    assertThat(captor.getValue().getCompletedAt()).isNull();
  }

  @Test
  void updateStatus_toDone_setsCompletedAt() {
    UUID taskId = UUID.randomUUID();
    Task task =
        Task.builder()
            .id(taskId)
            .tripId(TRIP_ID)
            .title("Pack bags")
            .status(TaskStatus.IN_PROGRESS)
            .priority(TaskPriority.MEDIUM)
            .assigneeId(UUID.fromString(DEVICE_ID))
            .createdBy(UUID.fromString(DEVICE_ID))
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, UUID.randomUUID().toString()));
    ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
    when(taskRepository.save(captor.capture())).thenAnswer(inv -> captor.getValue());

    TaskResponse response = service.updateStatus(taskId, DEVICE_ID, TaskStatus.DONE);

    assertThat(response.getStatus()).isEqualTo(TaskStatus.DONE);
    assertThat(captor.getValue().getCompletedAt()).isNotNull();
  }

  @Test
  void updateStatus_doneBackToTodo_clearsCompletedAt() {
    UUID taskId = UUID.randomUUID();
    Task task =
        Task.builder()
            .id(taskId)
            .tripId(TRIP_ID)
            .title("Reserve hotel")
            .status(TaskStatus.DONE)
            .priority(TaskPriority.MEDIUM)
            .assigneeId(UUID.fromString(DEVICE_ID))
            .completedAt(Instant.now())
            .createdBy(UUID.fromString(DEVICE_ID))
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, UUID.randomUUID().toString()));
    ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
    when(taskRepository.save(captor.capture())).thenAnswer(inv -> captor.getValue());

    service.updateStatus(taskId, DEVICE_ID, TaskStatus.TODO);

    assertThat(captor.getValue().getCompletedAt()).isNull();
  }

  @Test
  void updateStatus_byOrganizerNotAssignee_succeeds() {
    UUID taskId = UUID.randomUUID();
    UUID otherAssignee = UUID.randomUUID();
    Task task =
        Task.builder()
            .id(taskId)
            .tripId(TRIP_ID)
            .title("Arrange transport")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.HIGH)
            .assigneeId(otherAssignee)
            .createdBy(otherAssignee)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.ORGANIZER, UUID.randomUUID().toString()));
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

    TaskResponse response = service.updateStatus(taskId, DEVICE_ID, TaskStatus.IN_PROGRESS);

    assertThat(response.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    verify(taskRepository).save(any(Task.class));
  }

  @Test
  void updateStatus_byMemberNeitherAssigneeNorOrganizer_throws403() {
    UUID taskId = UUID.randomUUID();
    UUID otherAssignee = UUID.randomUUID();
    Task task =
        Task.builder()
            .id(taskId)
            .tripId(TRIP_ID)
            .title("Get groceries")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.LOW)
            .assigneeId(otherAssignee)
            .createdBy(otherAssignee)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, UUID.randomUUID().toString()));

    assertThatThrownBy(() -> service.updateStatus(taskId, DEVICE_ID, TaskStatus.IN_PROGRESS))
        .isInstanceOf(AccessDeniedException.class);

    verify(taskRepository, never()).save(any());
  }

  @Test
  void updateStatus_byNonMember_throws403() {
    UUID taskId = UUID.randomUUID();
    Task task =
        Task.builder()
            .id(taskId)
            .tripId(TRIP_ID)
            .title("Book flights")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.HIGH)
            .assigneeId(UUID.fromString(DEVICE_ID))
            .createdBy(UUID.fromString(DEVICE_ID))
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenThrow(new AccessDeniedException("Not a member of this trip"));

    assertThatThrownBy(() -> service.updateStatus(taskId, DEVICE_ID, TaskStatus.DONE))
        .isInstanceOf(AccessDeniedException.class);

    verify(taskRepository, never()).save(any());
  }

  @Test
  void updateStatus_taskNotFound_throws404() {
    UUID missingId = UUID.randomUUID();
    when(taskRepository.findById(missingId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateStatus(missingId, DEVICE_ID, TaskStatus.DONE))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Task not found");

    verify(taskRepository, never()).save(any());
  }

  @Test
  void updateStatus_unassignedTask_byOrganizer_succeeds_byPlainMember_throws403() {
    UUID taskId = UUID.randomUUID();
    Task task =
        Task.builder()
            .id(taskId)
            .tripId(TRIP_ID)
            .title("Unassigned task")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .assigneeId(null)
            .createdBy(UUID.fromString(DEVICE_ID))
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    // Organizer path — should succeed
    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.ORGANIZER, UUID.randomUUID().toString()));
    when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

    TaskResponse response = service.updateStatus(taskId, DEVICE_ID, TaskStatus.IN_PROGRESS);
    assertThat(response.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);

    // Reset and test plain member path — should throw
    reset(taskRepository, tripClient);
    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, UUID.randomUUID().toString()));

    assertThatThrownBy(() -> service.updateStatus(taskId, DEVICE_ID, TaskStatus.IN_PROGRESS))
        .isInstanceOf(AccessDeniedException.class);

    verify(taskRepository, never()).save(any());
  }

  // ─── Filter tests ────────────────────────────────────────────────────────

  @Test
  void listTasks_noFilters_returnsAllTopLevel_nested() {
    Instant now = Instant.now();
    UUID parentId = UUID.randomUUID();
    Task parent =
        Task.builder()
            .id(parentId)
            .tripId(TRIP_ID)
            .title("Top level")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(now)
            .updatedAt(now)
            .build();
    Task child =
        Task.builder()
            .id(UUID.randomUUID())
            .tripId(TRIP_ID)
            .parentTaskId(parentId)
            .title("Child task")
            .status(TaskStatus.TODO)
            .priority(TaskPriority.MEDIUM)
            .createdBy(UUID.randomUUID())
            .createdAt(now)
            .updatedAt(now)
            .build();

    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findTopLevelFiltered(TRIP_ID, null, null)).thenReturn(List.of(parent));
    when(taskRepository.findByParentTaskIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(child));

    List<TaskResponse> result = service.listTasks(TRIP_ID, DEVICE_ID, null, null);

    verify(taskRepository).findTopLevelFiltered(TRIP_ID, null, null);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getSubtasks()).hasSize(1);
  }

  @Test
  void listTasks_assigneeFilter_passesAssigneeToRepo() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findTopLevelFiltered(TRIP_ID, ASSIGNEE_ID, null)).thenReturn(List.of());

    service.listTasks(TRIP_ID, DEVICE_ID, ASSIGNEE_ID, null);

    verify(taskRepository).findTopLevelFiltered(TRIP_ID, ASSIGNEE_ID, null);
  }

  @Test
  void listTasks_statusFilter_passesStatusToRepo() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findTopLevelFiltered(TRIP_ID, null, TaskStatus.IN_PROGRESS))
        .thenReturn(List.of());

    service.listTasks(TRIP_ID, DEVICE_ID, null, TaskStatus.IN_PROGRESS);

    verify(taskRepository).findTopLevelFiltered(TRIP_ID, null, TaskStatus.IN_PROGRESS);
  }

  @Test
  void listTasks_bothFilters_combineAnd() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findTopLevelFiltered(TRIP_ID, ASSIGNEE_ID, TaskStatus.DONE))
        .thenReturn(List.of());

    service.listTasks(TRIP_ID, DEVICE_ID, ASSIGNEE_ID, TaskStatus.DONE);

    verify(taskRepository).findTopLevelFiltered(TRIP_ID, ASSIGNEE_ID, TaskStatus.DONE);
  }

  @Test
  void listTasks_noMatch_returnsEmpty_noChildQuery() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(taskRepository.findTopLevelFiltered(TRIP_ID, ASSIGNEE_ID, TaskStatus.IN_PROGRESS))
        .thenReturn(List.of());

    List<TaskResponse> result =
        service.listTasks(TRIP_ID, DEVICE_ID, ASSIGNEE_ID, TaskStatus.IN_PROGRESS);

    assertThat(result).isEmpty();
    verify(taskRepository, never()).findByParentTaskIdInOrderByCreatedAtAsc(any());
  }

  @Test
  void listTasks_nonMember_throws403() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.listTasks(TRIP_ID, DEVICE_ID, ASSIGNEE_ID, TaskStatus.TODO))
        .isInstanceOf(AccessDeniedException.class);

    verify(taskRepository, never()).findTopLevelFiltered(any(), any(), any());
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
