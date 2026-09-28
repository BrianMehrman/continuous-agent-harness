package com.brianmehrman.harness.runner;
import com.brianmehrman.harness.HarnessApplication;
import java.nio.file.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
public final class RunnerRecoveryProcess {
 public static void main(String[] args) { SpringApplication.run(new Class<?>[]{HarnessApplication.class,Faults.class},args); }
 @TestConfiguration @Profile("recovery-test")
 static class Faults {
  @Bean RunnerBoundary boundary() {
   return (point,id) -> {
    if(!point.equals(System.getenv("RUNNER_TEST_BOUNDARY")) || !id.equals(System.getenv("RUNNER_TEST_ID"))) return;
    try {
     Files.writeString(Path.of(System.getenv("RUNNER_TEST_MARKER")),point);
     while(true) Thread.sleep(1000);
    } catch(Exception e) { throw new IllegalStateException(e); }
   };
  }
 }
}
