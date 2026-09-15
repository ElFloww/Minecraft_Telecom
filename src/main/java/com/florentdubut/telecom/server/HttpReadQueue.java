package com.florentdubut.telecom.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class HttpReadQueue {
    private static final int MAX_COMPLETED = 64;
    private static final long RETENTION_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    @FunctionalInterface
    interface Work<T> {
        // Return an immutable snapshot, never a live world object.
        T run(ServerLevel level, long deadlineNanos);
    }

    record Completed<T>(T value, long ageNanos) {}

    static final class Pending extends RuntimeException {
        Pending() {
            super("Read pending", null, false, false);
        }
    }

    static final class Unavailable extends RuntimeException {
        Unavailable() {
            super("Minecraft server unavailable");
        }

        Unavailable(RejectedExecutionException cause) {
            super("Minecraft server rejected read", cause);
        }
    }

    private record Result(Object value, Throwable failure, long completedAt) {}

    private final LongSupplier nanoTime;
    private final LinkedHashMap<String, Result> completed = new LinkedHashMap<>();
    private FutureTask<Void> pending;
    private long generation;

    HttpReadQueue() {
        this(System::nanoTime);
    }

    HttpReadQueue(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    // A key must always identify the same snapshot type.
    @SuppressWarnings("unchecked")
    <T> Completed<T> request(String key, MinecraftServer server, Work<T> work) {
        if (server == null || server.isStopped()) throw new Unavailable();
        Objects.requireNonNull(key);
        Objects.requireNonNull(work);
        FutureTask<Void> task;
        synchronized (this) {
            long now = nanoTime.getAsLong();
            expire(now);
            Result result = completed.remove(key);
            if (result != null) {
                if (result.failure() instanceof RuntimeException failure) throw failure;
                if (result.failure() instanceof Error failure) throw failure;
                return new Completed<>((T) result.value(), now - result.completedAt());
            }
            if (pending != null) throw new Pending();
            long token = generation;
            task = new FutureTask<>(() -> {
                runWork(key, server, work, token);
                return null;
            });
            pending = task;
        }
        try {
            server.execute(() -> {
                try {
                    task.run();
                } finally {
                    synchronized (this) {
                        if (pending == task) pending = null;
                    }
                }
            });
        } catch (RejectedExecutionException failure) {
            task.cancel(false);
            synchronized (this) {
                if (pending == task) pending = null;
            }
            throw new Unavailable(failure);
        }
        throw new Pending();
    }

    private <T> void runWork(String key, MinecraftServer server, Work<T> work, long token) {
        Object value = null;
        Throwable failure = null;
        try {
            ServerLevel level;
            long deadline;
            synchronized (this) {
                if (token != generation) return;
                if (server.isStopped()) throw new Unavailable();
                deadline = nanoTime.getAsLong() + BUDGET_NANOS;
                // Serialize world acquisition with clear(), but never hold the lock during Work.
                level = server.overworld();
            }
            if (level == null) throw new Unavailable();
            value = work.run(level, deadline);
        } catch (RuntimeException | Error problem) {
            failure = problem;
        }
        Result result = new Result(value, failure, nanoTime.getAsLong());
        synchronized (this) {
            if (token != generation) return;
            expire(result.completedAt());
            if (completed.size() == MAX_COMPLETED) completed.pollFirstEntry();
            completed.put(key, result);
        }
    }

    private void expire(long now) {
        completed.values().removeIf(result -> now - result.completedAt() >= RETENTION_NANOS);
    }

    synchronized void clear() {
        generation++;
        completed.clear();
        // Cancellation invalidates queued work, not its slot: only the wrapper releases that.
        // Work already executing is not interrupted, but its result cannot be published.
        if (pending != null) pending.cancel(false);
    }
}
