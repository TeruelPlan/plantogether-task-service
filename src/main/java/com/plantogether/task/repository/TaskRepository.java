package com.plantogether.task.repository;

import com.plantogether.task.domain.Task;
import com.plantogether.task.domain.TaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
