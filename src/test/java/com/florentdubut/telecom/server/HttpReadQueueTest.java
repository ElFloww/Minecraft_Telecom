package com.florentdubut.telecom.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class HttpReadQueueTest {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final HttpReadQueue queue = new HttpReadQueue(clock::get);
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final ServerLevel level = mock(ServerLevel.class);
    private final ConcurrentLinkedQueue<Runnable> jobs = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final HttpReadQueue.Work<String> work = (world, deadline) -> {
        assertSame(level, world);
        calls.incrementAndGet();
        return "snapshot";
    };

    @BeforeEach
    void setUp() {
        when(server.overworld()).thenReturn(level);
        doAnswer(invocation -> {
            jobs.add(invocation.getArgument(0));
            return null;
        }).when(server).execute(any(Runnable.class));
    }

    @Test
    void pausedServerAcceptsOneCallbackForAThousandRequests() {
        for (int i = 0; i < 1000; i++) {
            String key = i % 2 == 0 ? "network" : "tile-" + i;
            assertThrows(HttpReadQueue.Pending.class, () -> queue.request(key, server, work));
        }
        assertEquals(1, jobs.size());
        assertEquals(0, calls.get());
        verify(server, times(1)).execute(any(Runnable.class));
        verify(server, never()).overworld();
        verifyNoInteractions(level);
    }

    @Test
    void resumeReturnsCompletedSnapshotWithoutRecalculationAndConsumesIt() {
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, work));
        clock.addAndGet(TimeUnit.HOURS.toNanos(1));
        jobs.remove().run();
        clock.addAndGet(123);
        var result = queue.request("network", server, work);
        assertEquals("snapshot", result.value());
        assertEquals(123, result.ageNanos());
        assertEquals(1, calls.get());
        assertTrue(jobs.isEmpty());
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, work));
        assertEquals(1, jobs.size());
    }

    @Test
    void duplicateKeyKeepsOriginalWorkAndCallbackRunsOnlyOnce() {
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, work));
        HttpReadQueue.Work<String> duplicate = (world, deadline) -> fail("Duplicate work ran");
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, duplicate));
        Runnable job = jobs.remove();
        job.run();
        job.run();
        assertEquals("snapshot", queue.request("network", server, duplicate).value());
        assertEquals(1, calls.get());
        verify(server, times(1)).execute(any(Runnable.class));
    }

    @Test
    void deadlineStartsAtExecutionAndAgeStartsAtCompletion() {
        AtomicLong deadlineSeen = new AtomicLong();
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, (world, deadline) -> {
            deadlineSeen.set(deadline);
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(4));
            return "snapshot";
        }));
        clock.addAndGet(TimeUnit.DAYS.toNanos(1));
        long executionTime = clock.get();
        jobs.remove().run();
        assertEquals(executionTime + TimeUnit.MILLISECONDS.toNanos(10), deadlineSeen.get());
        clock.addAndGet(71);
        assertEquals(71, queue.request("network", server, work).ageNanos());
    }

    @Test
    void defaultClockUsesSystemNanoTimeForDeadline() {
        HttpReadQueue systemQueue = new HttpReadQueue();
        AtomicLong observed = new AtomicLong();
        assertThrows(HttpReadQueue.Pending.class, () -> systemQueue.request("network", server, (world, deadline) -> {
            observed.set(deadline);
            return "snapshot";
        }));
        long before = System.nanoTime();
        jobs.remove().run();
        long after = System.nanoTime();
        long executionTime = observed.get() - TimeUnit.MILLISECONDS.toNanos(10);
        assertTrue(executionTime - before >= 0);
        assertTrue(after - executionTime >= 0);
    }

    @Test
    void runtimeFailureIsPreservedAndConsumedExactlyOnce() {
        IllegalArgumentException failure = new IllegalArgumentException("Invalid snapshot");
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("bad", server, (world, deadline) -> {
            throw failure;
        }));
        assertDoesNotThrow(() -> jobs.remove().run());
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> queue.request("bad", server, work)));
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("bad", server, work));
        jobs.remove().run();
        assertEquals("snapshot", queue.request("bad", server, work).value());
    }

    @Test
    void errorsAreNotSilentlyLostInFutureTask() {
        AssertionError failure = new AssertionError("Broken snapshot");
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("bad", server, (world, deadline) -> {
            throw failure;
        }));
        jobs.remove().run();
        assertSame(failure, assertThrows(AssertionError.class, () -> queue.request("bad", server, work)));
    }

    @Test
    void nullValueIsACompletedResult() {
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("null", server, (world, deadline) -> null));
        jobs.remove().run();
        assertNull(queue.request("null", server, work).value());
        assertTrue(jobs.isEmpty());
    }

    @Test
    void nullAndStoppedServersAreUnavailableWithoutScheduling() {
        assertThrows(HttpReadQueue.Unavailable.class, () -> queue.request("network", null, work));
        when(server.isStopped()).thenReturn(true);
        assertThrows(HttpReadQueue.Unavailable.class, () -> queue.request("network", server, work));
        verify(server, never()).execute(any(Runnable.class));
        verify(server, never()).overworld();
    }

    @Test
    void stoppingBeforeCallbackPreventsWorldAccess() {
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, work));
        when(server.isStopped()).thenReturn(true);
        jobs.remove().run();
        when(server.isStopped()).thenReturn(false);
        assertThrows(HttpReadQueue.Unavailable.class, () -> queue.request("network", server, work));
        verify(server, never()).overworld();
        assertEquals(0, calls.get());
    }

    @Test
    void missingWorldIsUnavailableOnConsumption() {
        when(server.overworld()).thenReturn(null);
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, work));
        jobs.remove().run();
        assertThrows(HttpReadQueue.Unavailable.class, () -> queue.request("network", server, work));
        assertEquals(0, calls.get());
    }

    @Test
    void rejectionPreservesCauseAndReleasesSlot() {
        RejectedExecutionException failure = new RejectedExecutionException("Stopped executor");
        doThrow(failure).when(server).execute(any(Runnable.class));
        var unavailable = assertThrows(HttpReadQueue.Unavailable.class, () -> queue.request("network", server, work));
        assertSame(failure, unavailable.getCause());
        setUp();
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, work));
        jobs.remove().run();
        assertEquals("snapshot", queue.request("network", server, work).value());
    }

    @Test
    void cacheNeverExceeds64AndEvictsOldestCompletion() throws Exception {
        for (int i = 0; i < 65; i++) {
            String key = "tile-" + i;
            assertThrows(HttpReadQueue.Pending.class, () -> queue.request(key, server, work));
            jobs.remove().run();
            clock.incrementAndGet();
            assertEquals(Math.min(i + 1, 64), completedCount());
        }
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("tile-0", server, work));
        for (int i = 1; i < 65; i++) {
            assertEquals("snapshot", queue.request("tile-" + i, server, work).value());
        }
        assertEquals(0, completedCount());
        assertEquals(1, jobs.size());
    }

    @Test
    void completedResultsExpireAt30SecondsIncludingFailures() throws Exception {
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("old", server, work));
        jobs.remove().run();
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("failure", server, (world, deadline) -> {
            throw new IllegalArgumentException("Expired failure");
        }));
        jobs.remove().run();
        clock.incrementAndGet();
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("young", server, work));
        jobs.remove().run();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(30) - 1);
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("old", server, work));
        assertEquals(1, completedCount());
        assertEquals(TimeUnit.SECONDS.toNanos(30) - 1, queue.request("young", server, work).ageNanos());
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("failure", server, work));
        assertEquals(0, completedCount());
        assertEquals(1, jobs.size());
    }

    @Test
    void overduePendingNeverAccumulatesEvenWhenCacheExpires() throws Exception {
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("done", server, work));
        jobs.remove().run();
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("pending", server, work));
        for (int i = 0; i < 1000; i++) {
            clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
            String key = "abandoned-" + i;
            assertThrows(HttpReadQueue.Pending.class, () -> queue.request(key, server, work));
        }
        assertEquals(0, completedCount());
        assertEquals(1, jobs.size());
        assertEquals(1, calls.get());
        jobs.remove().run();
        assertEquals("snapshot", queue.request("pending", server, work).value());
        assertEquals(2, calls.get());
    }

    @Test
    void clearInvalidatesOldCallbackWithoutFreeingItsSlotEarly() throws Exception {
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("done", server, work));
        jobs.remove().run();
        clearInvocations(server);
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("old", server, work));
        Runnable old = jobs.remove();
        for (int i = 0; i < 1000; i++) {
            queue.clear();
            assertThrows(HttpReadQueue.Pending.class, () -> queue.request("new", server, work));
        }
        assertEquals(0, completedCount());
        assertTrue(jobs.isEmpty());
        old.run();
        verify(server, never()).overworld();
        verify(server, times(1)).execute(any(Runnable.class));
        assertEquals(1, calls.get());
        MinecraftServer restarted = mock(MinecraftServer.class);
        when(restarted.overworld()).thenReturn(level);
        doAnswer(invocation -> { jobs.add(invocation.getArgument(0)); return null; })
                .when(restarted).execute(any(Runnable.class));
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("new", restarted, work));
        old.run();
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("other", restarted, work));
        assertEquals(1, jobs.size());
        jobs.remove().run();
        assertEquals("snapshot", queue.request("new", restarted, work).value());
    }

    @Test
    void concurrentRequestsScheduleOnlyOneCallbackAndConsumeOnce() throws Exception {
        try (var workers = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            ArrayList<Future<?>> requests = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                String key = i % 2 == 0 ? "network" : "tile-" + i;
                requests.add(workers.submit(() -> {
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    assertThrows(HttpReadQueue.Pending.class, () -> queue.request(key, server, work));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> request : requests) request.get(2, TimeUnit.SECONDS);
            assertEquals(1, jobs.size());
            jobs.remove().run();
            // Clear the arbitrary winning key and prepare a known result for concurrent consumers.
            queue.clear();
            assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, work));
            jobs.remove().run();
            AtomicInteger consumed = new AtomicInteger();
            requests.clear();
            for (int i = 0; i < 1000; i++) {
                requests.add(workers.submit(() -> {
                    try {
                        assertEquals("snapshot", queue.request("network", server, work).value());
                        consumed.incrementAndGet();
                    } catch (HttpReadQueue.Pending expected) {
                        // Pending is the nonblocking response for all other consumers.
                    }
                }));
            }
            for (Future<?> request : requests) request.get(2, TimeUnit.SECONDS);
            assertEquals(1, consumed.get());
            assertEquals(1, jobs.size());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void worldWorkDoesNotHoldLockAndClearDiscardsRunningResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, (world, deadline) -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return "obsolete";
        }));
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> running = workers.submit(jobs.remove());
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                workers.submit(() -> {
                    assertThrows(HttpReadQueue.Pending.class, () -> queue.request("other", server, work));
                    queue.clear();
                    assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, work));
                }).get(2, TimeUnit.SECONDS);
                assertTrue(jobs.isEmpty());
            } finally {
                release.countDown();
            }
            running.get(2, TimeUnit.SECONDS);
        }
        assertEquals(0, completedCount());
        assertThrows(HttpReadQueue.Pending.class, () -> queue.request("network", server, work));
        jobs.remove().run();
        assertEquals("snapshot", queue.request("network", server, work).value());
    }

    private int completedCount() throws Exception {
        var field = HttpReadQueue.class.getDeclaredField("completed");
        field.setAccessible(true);
        synchronized (queue) {
            return ((Map<?, ?>) field.get(queue)).size();
        }
    }
}
