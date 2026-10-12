package com.brianmehrman.harness.recovery;

import com.brianmehrman.harness.HarnessApplication;
import org.springframework.boot.SpringApplication;

/** Child JVM running the real production worker and Activities with the recovery test adapter profile. */
public final class CodingRecoveryWorkerProcess {
    private CodingRecoveryWorkerProcess() {}

    public static void main(String[] args) {
        SpringApplication.run(HarnessApplication.class, args);
    }
}
