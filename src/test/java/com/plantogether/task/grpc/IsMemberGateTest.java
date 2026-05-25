package com.plantogether.task.grpc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plantogether.common.grpc.InProcessTripClient;
import com.plantogether.common.grpc.TripClientTestSupport;
import com.plantogether.task.controller.TaskController;
import com.plantogether.task.domain.Task;
import com.plantogether.task.exception.GlobalExceptionHandler;
import com.plantogether.task.repository.TaskRepository;
import com.plantogether.task.service.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IsMemberGateTest {

  private static final String DEVICE_ID = UUID.randomUUID().toString();
  private static final UUID TRIP_ID = UUID.randomUUID();

  private InProcessTripClient tripClient;
  private TaskRepository taskRepository;
  private MockMvc mockMvc;
  private Authentication authentication;

  @BeforeEach
  void setUp() {
    taskRepository = mock(TaskRepository.class);
    ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    when(taskRepository.save(any(Task.class)))
        .thenAnswer(
            inv -> {
              Task t = inv.getArgument(0);
              t.setId(UUID.randomUUID());
              t.setCreatedAt(Instant.now());
              t.setUpdatedAt(Instant.now());
              return t;
            });

    tripClient = TripClientTestSupport.member(TRIP_ID.toString(), DEVICE_ID);

    TaskService service = new TaskService(taskRepository, tripClient, eventPublisher);
    TaskController controller = new TaskController(service);

    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    authentication =
        new UsernamePasswordAuthenticationToken(
            DEVICE_ID, "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @AfterEach
  void tearDown() throws Exception {
    SecurityContextHolder.clearContext();
    tripClient.close();
  }

  @Test
  void create_byMember_returns201() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", TRIP_ID)
                .principal(authentication)
                .header("X-Device-Id", DEVICE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "title": "Book hotel" }
                    """))
        .andExpect(status().isCreated());

    verify(taskRepository).save(any(Task.class));
  }

  @Test
  void create_byNonMember_returns403() throws Exception {
    UUID otherTrip = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/tasks", otherTrip)
                .principal(authentication)
                .header("X-Device-Id", DEVICE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "title": "Book hotel" }
                    """))
        .andExpect(status().isForbidden());

    verify(taskRepository, never()).save(any(Task.class));
  }
}
