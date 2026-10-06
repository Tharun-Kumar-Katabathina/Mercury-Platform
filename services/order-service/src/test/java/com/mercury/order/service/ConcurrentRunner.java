package com.mercury.order.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntFunction;

/** Releases N tasks at the same instant and collects each task's result or failure. */
final class ConcurrentRunner {

    record Outcome<T>(T value, Throwable failure) {
        boolean succeeded() {
            return failure == null;
        }
    }

    private ConcurrentRunner() {
    }

    static <T> List<Outcome<T>> runAll(int tasks, IntFunction<Callable<T>> taskFactory)
            throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        CountDownLatch ready = new CountDownLatch(tasks);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>();

        for (int i = 0; i < tasks; i++) {
            Callable<T> task = taskFactory.apply(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return new Outcome<>(task.call(), null);
                } catch (Throwable t) {
                    return new Outcome<>(null, t);
                }
            }));
        }

        ready.await();
        go.countDown();

        List<Outcome<T>> outcomes = new ArrayList<>();
        for (Future<Outcome<T>> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }
}
