package com.mercury.recommendation;

import com.mercury.recommendation.service.IndexSync;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskExecutionOutcome.Status;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;

/** Waiting for what another thread does, on a condition instead of on a sleep. */
final class Waiting {

    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final Duration LOOK_EVERY = Duration.ofMillis(10);

    private Waiting() {
    }

    /**
     * Until the thread is parked somewhere beneath the given method: on a lock, on another thread's result, or on an
     * answer that is being held back. From outside, that is the only way to see that a caller "is waiting".
     */
    static void untilParkedIn(Thread thread, Class<?> type, String method) {
        await().pollInterval(LOOK_EVERY).atMost(PATIENCE).until(() -> parkedIn(thread, type, method));
    }

    private static boolean parkedIn(Thread thread, Class<?> type, String method) {
        Thread.State state = thread.getState();
        if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
            return false;
        }
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals(type.getName()) && frame.getMethodName().equals(method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The scheduler starts one sync pass as soon as the context is up (the next one is an hour away in tests). A test
     * that drives passes itself, and counts what each of them pushed, starts once that one is over.
     */
    static void untilTheStartupSyncIsOver(ScheduledTaskHolder scheduler) {
        String sync = IndexSync.class.getName() + ".run";
        await().pollInterval(LOOK_EVERY).atMost(PATIENCE).until(() -> {
            List<Status> passes = scheduler.getScheduledTasks().stream().map(ScheduledTask::getTask)
                    .filter(task -> task.getRunnable().toString().equals(sync))
                    .map(task -> task.getLastExecutionOutcome().status()).toList();
            return !passes.isEmpty() && passes.stream().allMatch(status -> status == Status.SUCCESS || status == Status.ERROR);
        });
    }
}
