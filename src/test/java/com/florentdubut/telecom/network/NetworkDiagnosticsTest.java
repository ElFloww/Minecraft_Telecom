package com.florentdubut.telecom.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static com.florentdubut.telecom.network.NetworkDiagnostics.Cause.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkDiagnosticsTest {
    private final AtomicLong clock = new AtomicLong(100);
    private final AtomicLong bytes = new AtomicLong(1000);
    private final NetworkDiagnostics diagnostics = new NetworkDiagnostics(clock::get, bytes::get);

    @Test
    void disabledDiagnosticsNeverReadMeasurementSourcesOrSuppressWork() {
        LongSupplier clock = mock(LongSupplier.class);
        LongSupplier bytes = mock(LongSupplier.class);
        NetworkDiagnostics disabled = new NetworkDiagnostics(clock, bytes);
        Runnable work = mock(Runnable.class);

        assertFalse(disabled.isEnabled());
        disabled.request(CABLE_PLACE, false);
        disabled.request(PLAYER_LOGIN, true);
        disabled.delayedReady();
        disabled.nextTick();
        disabled.startServerTick();
        disabled.finishServerTick();
        disabled.runScheduled(work);
        assertNull(disabled.begin(2, 1));
        disabled.finish(null, false, 0);

        verify(work).run();
        verifyNoInteractions(clock, bytes);
        assertTrue(disabled.requests().isEmpty());
        assertTrue(disabled.recalculations().isEmpty());
        assertEquals(0, disabled.serverTicks().samples());
        assertEquals(0, disabled.skippedTrafficTicks());
    }

    @Test
    void injectedMeasurementsUseFirstCoalescedRequestAndPublishTraceCounters() {
        diagnostics.start();
        diagnostics.request(CABLE_PLACE, false);
        diagnostics.nextTick();
        clock.set(130);
        diagnostics.request(CABLE_PLACE, false);
        diagnostics.request(NODE_LOAD, false);
        diagnostics.nextTick();
        clock.set(160);

        diagnostics.runScheduled(() -> {
            var trace = diagnostics.begin(3, 2);
            trace.steps = 7;
            trace.blockReads = 11;
            trace.chunkRequests = 13;
            trace.pathCopies = 5;
            trace.copiedReferences = 17;
            clock.set(200);
            bytes.set(1256);
            diagnostics.finish(trace, true, 4);
        });

        var causes = Map.of(CABLE_PLACE, 2L, NODE_LOAD, 1L);
        assertEquals(new NetworkDiagnostics.Recalculation(causes, true, 40, 60, 2, 100,
                256, 7, 11, 13, 5, 17, 3, 2, 4), diagnostics.recalculations().getFirst());
        assertEquals(causes, diagnostics.requests());
        assertEquals(1, diagnostics.skippedTrafficTicks());
    }

    @Test
    void percentilesUseNearestRankIncludingEmptyAndSingletonWindows() {
        assertEquals(new NetworkDiagnostics.Distribution(0, 0, 0, 0, 0),
                NetworkDiagnostics.distribution(new long[0]));
        assertEquals(new NetworkDiagnostics.Distribution(1, 42, 42, 42, 42),
                NetworkDiagnostics.distribution(new long[]{42}));
        long[] descending = new long[20];
        for (int i = 0; i < descending.length; i++) descending[i] = 20 - i;
        assertEquals(new NetworkDiagnostics.Distribution(20, 10, 19, 20, 20),
                NetworkDiagnostics.distribution(descending));
    }

    @Test
    void rollingWindowsEvictOldestSamplesButKeepLifetimeCounts() {
        diagnostics.start();
        assertEquals(128, NetworkDiagnostics.MAX_RECALCULATIONS);
        assertEquals(1200, NetworkDiagnostics.MAX_TICKS);
        for (int i = 1; i <= NetworkDiagnostics.MAX_RECALCULATIONS + 2; i++) {
            var trace = diagnostics.begin(0, 0);
            clock.addAndGet(i);
            diagnostics.finish(trace, i != 1, 0);
            if (i == NetworkDiagnostics.MAX_RECALCULATIONS) {
                assertEquals(i, diagnostics.recalculations().size());
                assertEquals(1, diagnostics.recalculations().getFirst().durationNanos());
            }
        }
        assertEquals(128, diagnostics.recalculations().size());
        assertEquals(3, diagnostics.recalculations().getFirst().durationNanos());
        assertEquals(130, diagnostics.recalculations().getLast().durationNanos());
        assertEquals(Map.of(EXPLICIT_RECALCULATION, 130L), diagnostics.requests());
        assertTrue(diagnostics.report().getFirst().contains("completed=129; failed=1"));

        for (int i = 0; i < NetworkDiagnostics.MAX_TICKS; i++) {
            diagnostics.startServerTick();
            clock.addAndGet(i == 0 ? 9999 : 1);
            diagnostics.finishServerTick();
        }
        assertEquals(1200, diagnostics.serverTicks().samples());
        assertEquals(9999, diagnostics.serverTicks().max());
        diagnostics.startServerTick();
        clock.addAndGet(2);
        diagnostics.finishServerTick();
        assertEquals(new NetworkDiagnostics.Distribution(1200, 1, 1, 1, 2), diagnostics.serverTicks());
    }

    @ParameterizedTest
    @CsvSource({"-1, 1100", "1000, -1", "1000, 999"})
    void unavailableOrRegressingAllocationCountersAreReportedAsMinusOne(long before, long after) {
        diagnostics.start();
        bytes.set(before);
        var trace = diagnostics.begin(0, 0);
        bytes.set(after);
        clock.addAndGet(25);
        diagnostics.finish(trace, true, 0);
        var result = diagnostics.recalculations().getFirst();
        assertTrue(result.success());
        assertEquals(25, result.publicationNanos());
        assertEquals(-1, result.allocatedBytes());
    }

    @Test
    void failedTraceHasNoPublicationAndScheduledExceptionDoesNotLeakItsCause() {
        diagnostics.start();
        diagnostics.request(CABLE_REMOVE, false);
        IllegalStateException failure = new IllegalStateException("trace failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> diagnostics.runScheduled(() -> {
            var trace = diagnostics.begin(2, 1);
            clock.addAndGet(15);
            diagnostics.finish(trace, false, 1);
            throw failure;
        })));
        var failed = diagnostics.recalculations().getFirst();
        assertFalse(failed.success());
        assertEquals(-1, failed.publicationNanos());
        assertEquals(15, failed.durationNanos());
        assertEquals(0, failed.allocatedBytes());
        diagnostics.finish(diagnostics.begin(2, 1), true, 1);
        assertEquals(Map.of(EXPLICIT_RECALCULATION, 1L), diagnostics.recalculations().getLast().causes());
        assertTrue(diagnostics.report().getFirst().contains("completed=1; failed=1"));
    }

    @Test
    void stopRetainsResultsAndStartResetsCountersBatchesAndUnfinishedTick() {
        diagnostics.start();
        diagnostics.runScheduled(() -> diagnostics.finish(diagnostics.begin(0, 0), true, 0));
        diagnostics.startServerTick();
        clock.addAndGet(8);
        diagnostics.finishServerTick();
        diagnostics.request(CABLE_PLACE, false);
        diagnostics.request(PLAYER_LOGIN, true);
        diagnostics.startServerTick();
        var results = diagnostics.recalculations();
        var requests = diagnostics.requests();

        diagnostics.stop();
        assertFalse(diagnostics.isEnabled());
        diagnostics.finishServerTick();
        diagnostics.request(NODE_REMOVE, false);
        assertNull(diagnostics.begin(0, 0));
        assertEquals(results, diagnostics.recalculations());
        assertEquals(requests, diagnostics.requests());
        assertEquals(1, diagnostics.serverTicks().samples());
        assertTrue(diagnostics.report().getFirst().contains("OFF; completed=1"));

        diagnostics.start();
        assertTrue(diagnostics.isEnabled());
        diagnostics.finishServerTick();
        assertTrue(diagnostics.recalculations().isEmpty());
        assertTrue(diagnostics.requests().isEmpty());
        assertEquals(0, diagnostics.serverTicks().samples());
        assertEquals(0, diagnostics.skippedTrafficTicks());
        assertTrue(diagnostics.report().getFirst().contains("completed=0; failed=0"));
        diagnostics.delayedReady();
        diagnostics.runScheduled(() -> diagnostics.finish(diagnostics.begin(0, 0), true, 0));
        assertEquals(Map.of(UNSPECIFIED, 1L), diagnostics.recalculations().getFirst().causes());
    }

    @Test
    void ticksRequireAPairedFinishAndClampNegativeDurations() {
        diagnostics.start();
        diagnostics.finishServerTick();
        assertEquals(0, diagnostics.serverTicks().samples());
        diagnostics.startServerTick();
        clock.set(90);
        diagnostics.finishServerTick();
        diagnostics.finishServerTick();
        assertEquals(new NetworkDiagnostics.Distribution(1, 0, 0, 0, 0), diagnostics.serverTicks());
    }

    @Test
    void delayedCausesStaySeparateUntilReadyThenMergeUsingOldestRequest() {
        diagnostics.start();
        diagnostics.request(PLAYER_LOGIN, true);
        clock.set(120);
        diagnostics.nextTick();
        diagnostics.request(CABLE_PLACE, false);
        diagnostics.runScheduled(() -> diagnostics.finish(diagnostics.begin(0, 0), true, 0));
        assertEquals(Map.of(CABLE_PLACE, 1L), diagnostics.recalculations().getFirst().causes());

        clock.set(140);
        diagnostics.nextTick();
        diagnostics.request(NODE_LOAD, false);
        diagnostics.request(PLAYER_LOGIN, true);
        diagnostics.delayedReady();
        diagnostics.delayedReady();
        clock.set(180);
        diagnostics.nextTick();
        diagnostics.runScheduled(() -> diagnostics.finish(diagnostics.begin(0, 0), true, 0));
        var merged = diagnostics.recalculations().getLast();
        assertEquals(Map.of(PLAYER_LOGIN, 2L, NODE_LOAD, 1L), merged.causes());
        assertEquals(80, merged.waitNanos());
        assertEquals(3, merged.waitTicks());
        assertEquals(Map.of(CABLE_PLACE, 1L, PLAYER_LOGIN, 2L, NODE_LOAD, 1L), diagnostics.requests());
    }
}
