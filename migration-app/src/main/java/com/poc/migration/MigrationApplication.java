package com.poc.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One application, four entry points: {@code classify}, {@code load}, {@code reconcile}, and
 * {@code migrate} - which is the first three in one process. Selected by the first command-line
 * argument.
 *
 * <p><b>There is deliberately no job worker in this application.</b> The design calls for explicit
 * activation of the held {@code migrator} job by the reconcile step, after the release gate passes.
 * A Spring {@code @JobWorker} on type {@code migrator} would poll continuously and complete held
 * jobs the instant they appeared, releasing every case before the gate ran - and the hold would
 * fail open, looking implemented while doing nothing.
 *
 * <p>If you are about to add a {@code @JobWorker} here, read that paragraph again.
 */
@SpringBootApplication
public class MigrationApplication {

    public static void main(String[] args) {
        // Not a server. Exit code carries the outcome so the CLI is scriptable.
        System.exit(SpringApplication.exit(SpringApplication.run(MigrationApplication.class, args)));
    }
}
