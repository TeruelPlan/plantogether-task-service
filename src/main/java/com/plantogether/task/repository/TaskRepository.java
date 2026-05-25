package com.plantogether.task.repository;

import com.plantogether.task.domain.Task;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, UUID> {

  List<Task> findByTripIdAndParentTaskIdIsNullOrderByCreatedAtDesc(UUID tripId);

  List<Task> findByParentTaskIdInOrderByCreatedAtAsc(Collection<UUID> parentTaskIds);
}
