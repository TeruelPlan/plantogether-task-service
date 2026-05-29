package com.plantogether.task.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.plantogether.task.domain.Task;
import com.plantogether.task.domain.TaskPriority;
import com.plantogether.task.domain.TaskStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
class TaskRepositoryReminderIT {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("plantogether_task")
          .withUsername("plantogether")
          .withPassword("plantogether");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private TaskRepository repository;

  private Task save(TaskStatus status, Instant deadline, UUID assigneeId, Instant reminderSentAt) {
    Task t =
        Task.builder()
            .tripId(UUID.randomUUID())
            .title("Test task")
            .status(status)
            .priority(TaskPriority.MEDIUM)
            .deadline(deadline)
            .assigneeId(assigneeId)
            .createdBy(UUID.randomUUID())
            .reminderSentAt(reminderSentAt)
            .build();
    return repository.save(t);
  }

  @Test
  void findDueForReminder_returnsOnlyEligibleTasks() {
    Instant now = Instant.now();
    Instant in12h = now.plus(12, ChronoUnit.HOURS);
    Instant in25h = now.plus(25, ChronoUnit.HOURS);
    UUID assignee = UUID.randomUUID();

    save(TaskStatus.TODO, in12h, assignee, null); // eligible
    save(TaskStatus.DONE, in12h, assignee, null); // excluded: DONE
    save(TaskStatus.TODO, null, assignee, null); // excluded: no deadline
    save(TaskStatus.TODO, in12h, null, null); // excluded: no assignee
    save(TaskStatus.TODO, in12h, assignee, now); // excluded: already reminded
    save(TaskStatus.TODO, in25h, assignee, null); // excluded: beyond window
    save(TaskStatus.TODO, now.minus(1, ChronoUnit.HOURS), assignee, null); // excluded: past

    List<Task> result = repository.findDueForReminder(now, now.plus(24, ChronoUnit.HOURS));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getStatus()).isEqualTo(TaskStatus.TODO);
    assertThat(result.get(0).getDeadline()).isEqualTo(in12h);
  }

  @Test
  void claimForReminder_firstCallReturns1_secondReturns0() {
    Instant now = Instant.now();
    UUID assignee = UUID.randomUUID();
    Task task = save(TaskStatus.TODO, now.plus(12, ChronoUnit.HOURS), assignee, null);

    int first = repository.claimForReminder(task.getId(), now);
    int second = repository.claimForReminder(task.getId(), now.plusSeconds(1));

    assertThat(first).isEqualTo(1);
    assertThat(second).isEqualTo(0);
  }

  @Test
  void claimForReminder_alreadyReminded_returns0() {
    Instant now = Instant.now();
    UUID assignee = UUID.randomUUID();
    Task task =
        save(TaskStatus.TODO, now.plus(12, ChronoUnit.HOURS), assignee, now.minusSeconds(60));

    int claimed = repository.claimForReminder(task.getId(), now);

    assertThat(claimed).isEqualTo(0);
  }
}
