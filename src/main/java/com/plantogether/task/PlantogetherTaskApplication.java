package com.plantogether.task;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PlantogetherTaskApplication {
  public static void main(String[] args) {
    SpringApplication.run(PlantogetherTaskApplication.class, args);
  }
}
