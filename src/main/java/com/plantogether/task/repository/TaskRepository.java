package com.plantogether.task.repository;

import com.plantogether.task.domain.Task;
import com.plantogether.task.domain.TaskStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, UUID> {

  List<Task> findByTripIdAndParentTaskIdIsNullOrderByCreatedAtDesc(UUID tripId);

  List<Task> findByParentTaskIdInOrderByCreatedAtAsc(Collection<UUID> parentTaskIds);

  @Query(
      """
      SELECT t FROM Task t
      WHERE t.tripId = :tripId
        AND t.parentTaskId IS NULL
        AND (:assignee IS NULL OR t.assigneeId = :assignee)
        AND (:status   IS NULL OR t.status     = :status)
      ORDER BY t.createdAt DESC
      """)
  List<Task> findTopLevelFiltered(
      @Param("tripId") UUID tripId,
      @Param("assignee") UUID assignee,
      @Param("status") TaskStatus status);

  @Query(
      """
      SELECT t FROM Task t
      WHERE t.deadline IS NOT NULL
        AND t.assigneeId IS NOT NULL
        AND t.status <> com.plantogether.task.domain.TaskStatus.DONE
        AND t.reminderSentAt IS NULL
        AND t.deadline <= :windowEnd
        AND t.deadline >= :now
      ORDER BY t.deadline ASC
      """)
  List<Task> findDueForReminder(@Param("now") Instant now, @Param("windowEnd") Instant windowEnd);

  @Modifying
  @Query("UPDATE Task t SET t.reminderSentAt = :now WHERE t.id = :id AND t.reminderSentAt IS NULL")
  int claimForReminder(@Param("id") UUID id, @Param("now") Instant now);
}
