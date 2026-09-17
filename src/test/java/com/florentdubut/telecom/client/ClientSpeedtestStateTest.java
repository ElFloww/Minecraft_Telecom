package com.florentdubut.telecom.client;

import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ClientSpeedtestStateTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final BlockPos POS = new BlockPos(17, 70, -32);
    private static final UUID PLAYER = new UUID(1, 2);
    private static final UUID FIRST = new UUID(5, 1);
    private static final UUID SECOND = new UUID(5, 2);
    private static final ClientSpeedtestState.Key ROUTER = ClientSpeedtestState.routerKey(OVERWORLD, POS);
    private static final ClientSpeedtestState.Key OTHER_ROUTER = ClientSpeedtestState.routerKey(OVERWORLD, POS.above());
    private static final ClientSpeedtestState.Key MOBILE = ClientSpeedtestState.mobileKey(OVERWORLD, PLAYER);
    private final AtomicLong now = new AtomicLong();
    private final Object connection = new Object();
    private ClientSpeedtestState.Cache cache;

    @BeforeEach
    void setUp() {
        cache = new ClientSpeedtestState.Cache(now::get);
        cache.connected(connection);
    }

    @Test
    void deviceKeysFollowServerIdentityNotIp() {
        assertEquals("router:" + POS.asLong(), ROUTER.deviceId());
        assertEquals("mobile:" + PLAYER, MOBILE.deviceId());
        assertTrue(ROUTER.matches(packet(ROUTER, FIRST, "PING")));
        assertFalse(ROUTER.matches(packet(OTHER_ROUTER, FIRST, "PING")));
        assertFalse(MOBILE.matches(packet(ROUTER, FIRST, "PING")));
        assertFalse(MOBILE.matches(packet(ClientSpeedtestState.mobileKey(OVERWORLD, new UUID(1, 3)), FIRST, "PING")));
    }

    @Test
    void twoRoutersAndMobileWithSameIpRemainIndependent() {
        var first = packet(ROUTER, FIRST, "DOWNLOAD");
        var second = packet(OTHER_ROUTER, SECOND, "UPLOAD");
        var mobile = packet(MOBILE, new UUID(5, 3), "PING");
        assertEquals(first.clientIp(), second.clientIp());
        assertEquals(first.clientIp(), mobile.clientIp());
        assertTrue(cache.accept(connection, first));
        assertTrue(cache.accept(connection, second));
        assertTrue(cache.accept(connection, mobile));
        assertSame(first, cache.get(ROUTER).payload());
        assertSame(second, cache.get(OTHER_ROUTER).payload());
        assertSame(mobile, cache.get(MOBILE).payload());
        assertEquals(3, cache.size());
    }

    @Test
    void reopeningReadsProgressAndThenCompleteResults() {
        assertTrue(cache.markPending(connection, ROUTER));
        assertTrue(cache.get(ROUTER).pending());
        var progress = packet(ROUTER, FIRST, "UPLOAD");
        cache.accept(connection, progress);
        var reopened = cache.get(ROUTER);
        assertTrue(reopened.active());
        assertFalse(reopened.pending());
        assertEquals(810, reopened.payload().downloadBandwidth());
        assertEquals(230, reopened.payload().uploadBandwidth());

        var finished = packet(ROUTER, FIRST, "FINISHED");
        cache.accept(connection, finished);
        assertSame(finished, cache.get(ROUTER).payload());
        assertFalse(cache.get(ROUTER).active());
        assertEquals(810, cache.get(ROUTER).payload().downloadBandwidth());
        assertEquals(230, cache.get(ROUTER).payload().uploadBandwidth());
    }

    @Test
    void finalPacketAloneContainsBothResults() {
        var finished = packet(MOBILE, FIRST, "FINISHED");
        assertTrue(cache.accept(connection, finished));
        assertEquals(810, cache.get(MOBILE).payload().downloadBandwidth());
        assertEquals(230, cache.get(MOBILE).payload().uploadBandwidth());
        assertFalse(cache.get(MOBILE).active());
        assertNull(cache.get(ROUTER).payload());
    }

    @Test
    void finishingAnotherDeviceDoesNotUnlockRunningOrPendingDevice() {
        cache.accept(connection, packet(ROUTER, FIRST, "DOWNLOAD"));
        cache.markPending(connection, MOBILE);
        cache.accept(connection, packet(OTHER_ROUTER, SECOND, "FINISHED"));
        assertTrue(cache.get(ROUTER).active());
        assertTrue(cache.get(MOBILE).pending());
        assertFalse(cache.get(OTHER_ROUTER).active());
    }

    @Test
    void dimensionChangesKeepSeparateHistoriesForSamePositionAndPlayer() {
        var netherRouter = ClientSpeedtestState.routerKey(NETHER, POS);
        var netherMobile = ClientSpeedtestState.mobileKey(NETHER, PLAYER);
        var oldWorldPacket = packet(ROUTER, FIRST, "DOWNLOAD");
        cache.accept(connection, oldWorldPacket);
        cache.accept(connection, packet(netherRouter, SECOND, "UPLOAD"));
        cache.markPending(connection, netherMobile);
        assertFalse(netherRouter.matches(oldWorldPacket));
        assertSame(oldWorldPacket, cache.get(ROUTER).payload());
        assertEquals("UPLOAD", cache.get(netherRouter).payload().state());
        assertNull(cache.get(MOBILE).payload());
        assertTrue(cache.get(netherMobile).pending());
    }

    @Test
    void lateOldSessionCannotReplaceNewSessionIncludingAfterItFinishes() {
        cache.accept(connection, packet(ROUTER, FIRST, "DOWNLOAD"));
        var newer = packet(ROUTER, SECOND, "PING");
        cache.accept(connection, newer);
        assertFalse(cache.accept(connection, packet(ROUTER, FIRST, "FINISHED")));
        assertFalse(cache.accept(connection, packet(ROUTER, FIRST, "UPLOAD")));
        assertSame(newer, cache.get(ROUTER).payload());
        var finished = packet(ROUTER, SECOND, "FINISHED");
        assertTrue(cache.accept(connection, finished));
        assertFalse(cache.accept(connection, packet(ROUTER, FIRST, "FINISHED")));
        assertSame(finished, cache.get(ROUTER).payload());
    }

    @Test
    void unseenTerminalCannotReplaceKnownActiveSession() {
        var active = packet(ROUTER, SECOND, "DOWNLOAD");
        cache.accept(connection, active);
        assertFalse(cache.accept(connection, packet(ROUTER, FIRST, "FINISHED")));
        assertSame(active, cache.get(ROUTER).payload());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FINISHED", "FAILED", "REJECTED"})
    void terminalStatesUnlockAndCannotRegress(String state) {
        cache.accept(connection, packet(ROUTER, FIRST, "UPLOAD"));
        var terminal = packet(ROUTER, FIRST, state);
        assertTrue(terminal.terminal());
        assertTrue(cache.accept(connection, terminal));
        assertFalse(cache.get(ROUTER).active());
        assertFalse(cache.accept(connection, packet(ROUTER, FIRST, "DOWNLOAD")));
        assertSame(terminal, cache.get(ROUTER).payload());
    }

    @Test
    void unansweredPendingRequestExpiresAfterExactlyThreeSeconds() {
        assertTrue(cache.markPending(connection, MOBILE));
        assertFalse(cache.markPending(connection, MOBILE));
        now.set(ClientSpeedtestState.PENDING_TIMEOUT_NANOS - 1);
        assertTrue(cache.get(MOBILE).active());
        now.incrementAndGet();
        assertFalse(cache.get(MOBILE).active());
        assertFalse(cache.get(MOBILE).pending());
        assertEquals("timeout", cache.get(MOBILE).errorCode());
        assertTrue(cache.markPending(connection, MOBILE));
        assertEquals("", cache.get(MOBILE).errorCode());
        assertTrue(cache.get(MOBILE).pending());
    }

    @Test
    void pendingTimeoutNeverExpiresServerSession() {
        cache.markPending(connection, ROUTER);
        cache.accept(connection, packet(ROUTER, FIRST, "PING"));
        now.set(100 * ClientSpeedtestState.PENDING_TIMEOUT_NANOS);
        assertTrue(cache.get(ROUTER).active());
        assertFalse(cache.get(ROUTER).pending());
        assertFalse(cache.markPending(connection, ROUTER));
    }

    @Test
    void rejectionWithoutSessionUnlocksPendingButDoesNotCancelKnownSession() {
        var rejected = packet(ROUTER, new UUID(0, 0), "REJECTED");
        cache.markPending(connection, ROUTER);
        assertTrue(cache.accept(connection, rejected));
        assertFalse(cache.get(ROUTER).active());
        assertSame(rejected, cache.get(ROUTER).payload());

        cache.markPending(connection, ROUTER);
        var active = packet(ROUTER, FIRST, "PING");
        cache.accept(connection, active);
        assertTrue(cache.accept(connection, rejected));
        assertTrue(cache.get(ROUTER).active());
        assertSame(active, cache.get(ROUTER).payload());
    }

    @Test
    void duplicateOldResultDoesNotAcknowledgeNewPendingRequest() {
        var finished = packet(ROUTER, FIRST, "FINISHED");
        cache.accept(connection, finished);
        cache.markPending(connection, ROUTER);
        assertFalse(cache.accept(connection, finished));
        assertTrue(cache.get(ROUTER).pending());
        now.set(ClientSpeedtestState.PENDING_TIMEOUT_NANOS);
        assertFalse(cache.get(ROUTER).active());
        assertSame(finished, cache.get(ROUTER).payload());
    }

    @Test
    void newRequestCanFailBeforeFirstProgressWithoutRestoringOldResults() {
        cache.accept(connection, packet(ROUTER, FIRST, "FINISHED"));
        cache.markPending(connection, ROUTER);
        var failed = packet(ROUTER, SECOND, "FAILED");
        assertTrue(cache.accept(connection, failed));
        assertSame(failed, cache.get(ROUTER).payload());
        assertFalse(cache.get(ROUTER).active());
        assertFalse(cache.get(ROUTER).pending());
        assertFalse(cache.accept(connection, packet(ROUTER, FIRST, "FINISHED")));
    }

    @Test
    void cacheAndSessionHistoryAreBoundedAndReadsRefreshLru() {
        cache.accept(connection, packet(ROUTER, FIRST, "PING"));
        for (int i = 0; i < ClientSpeedtestState.MAX_ENTRIES; i++) {
            cache.get(ROUTER);
            var key = ClientSpeedtestState.routerKey(OVERWORLD, new BlockPos(i, 0, 0));
            cache.accept(connection, packet(key, new UUID(10, i), "PING"));
        }
        assertEquals(ClientSpeedtestState.MAX_ENTRIES, cache.size());
        assertEquals(ClientSpeedtestState.MAX_ENTRIES, cache.sessionCount());
        assertNotNull(cache.get(ROUTER).payload());
        assertNull(cache.get(ClientSpeedtestState.routerKey(OVERWORLD, BlockPos.ZERO)).payload());
        assertTrue(cache.accept(connection, packet(ROUTER, FIRST, "FINISHED")),
                "The current session must still finish when its order-history entry was evicted");
    }

    @Test
    void manySessionsOnOneDeviceHaveBoundedHistory() {
        for (int i = 0; i < 3 * ClientSpeedtestState.MAX_ENTRIES; i++) {
            cache.accept(connection, packet(ROUTER, new UUID(10, i), "PING"));
        }
        assertEquals(1, cache.size());
        assertEquals(ClientSpeedtestState.MAX_ENTRIES, cache.sessionCount());
        assertFalse(cache.accept(connection, packet(ROUTER, new UUID(10, 0), "FINISHED")));
        assertTrue(cache.get(ROUTER).active());
    }

    @Test
    void pendingOnlyEntriesAreAlsoBounded() {
        for (int i = 0; i < 2 * ClientSpeedtestState.MAX_ENTRIES; i++) {
            cache.markPending(connection, ClientSpeedtestState.routerKey(OVERWORLD, new BlockPos(i, 0, 0)));
        }
        assertEquals(ClientSpeedtestState.MAX_ENTRIES, cache.size());
        assertEquals(0, cache.sessionCount());
    }

    @Test
    void logoutClearsAndRejectsOldWorldPayloadsEvenAfterReconnect() {
        var oldPayload = packet(ROUTER, FIRST, "DOWNLOAD");
        cache.accept(connection, oldPayload);
        cache.markPending(connection, MOBILE);
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.sessionCount());
        assertFalse(cache.accept(connection, oldPayload));
        assertFalse(cache.markPending(connection, ROUTER));
        assertFalse(cache.get(MOBILE).active());

        Object newConnection = new Object();
        cache.connected(newConnection);
        assertFalse(cache.accept(connection, oldPayload));
        assertNull(cache.get(ROUTER).payload());
        assertTrue(cache.accept(newConnection, packet(ROUTER, SECOND, "PING")));
    }

    @Test
    void newConnectionClearsStateEvenWithoutPriorLogout() {
        cache.accept(connection, packet(ROUTER, FIRST, "DOWNLOAD"));
        cache.connected(new Object());
        assertEquals(0, cache.size());
        assertEquals(0, cache.sessionCount());
        assertFalse(cache.get(ROUTER).active());
    }

    @Test
    void receptionHistoryIsBoundedImmutableAndNeverAppendedByReadsOrDuplicateSnapshots() {
        for (int tick = 0; tick < 150; tick++) {
            now.addAndGet(250_000_000L);
            assertTrue(cache.accept(connection, sample(FIRST, "DOWNLOAD", tick, tick * 10)));
        }
        var snapshot = cache.get(ROUTER);
        assertEquals(120, snapshot.download().size());
        assertEquals(new ClientSpeedtestState.Point(30, 300), snapshot.download().getFirst());
        assertEquals(new ClientSpeedtestState.Point(149, 1490), snapshot.download().getLast());
        assertEquals(1490, snapshot.maxObserved());
        assertTrue(snapshot.upload().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.download().clear());
        now.addAndGet(1_000_000_000L);
        assertFalse(cache.accept(connection, sample(FIRST, "DOWNLOAD", 149, 1490)));
        assertFalse(cache.accept(connection, sample(FIRST, "DOWNLOAD", 149, 9000)));
        assertFalse(cache.accept(connection, sample(FIRST, "DOWNLOAD", 1, 9000)));
        for (int frame = 0; frame < 1000; frame++) {
            var reopened = cache.get(ROUTER);
            assertSame(snapshot.download(), reopened.download());
            assertEquals(snapshot.receivedAt(), reopened.receivedAt());
        }
    }

    @Test
    void phaseSamplesAreIndependentAndFinishedMeansAreExactNotCurveAverages() {
        cache.accept(connection, sample(FIRST, "PING", 10, 0));
        assertTrue(cache.get(ROUTER).download().isEmpty());
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 0, 950));
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 20, 1000));
        var down = cache.get(ROUTER).download();
        cache.accept(connection, sample(FIRST, "UPLOAD", 0, 300));
        assertFalse(cache.accept(connection, sample(FIRST, "DOWNLOAD", 21, 1000)));
        for (int tick = 1; tick <= 140; tick++) cache.accept(connection, sample(FIRST, "UPLOAD", tick, 350));
        assertSame(down, cache.get(ROUTER).download());
        assertEquals(120, cache.get(ROUTER).upload().size());
        assertEquals(21, cache.get(ROUTER).upload().getFirst().ticksElapsed());
        var up = cache.get(ROUTER).upload();
        var finished = sample(FIRST, "FINISHED", 0, 999);
        cache.accept(connection, finished);
        var snapshot = cache.get(ROUTER);
        assertSame(down, snapshot.download());
        assertSame(up, snapshot.upload());
        assertEquals(810, snapshot.payload().downloadBandwidth());
        assertEquals(230, snapshot.payload().uploadBandwidth());
        assertEquals(0, snapshot.instantaneous(now.get()));
        assertEquals(100, snapshot.percent(now.get()));
        assertFalse(snapshot.waiting(Long.MAX_VALUE));
    }

    @Test
    void oldSessionsAndRefusedRequestsCannotCorruptPreviousHistory() {
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 10, 800));
        var original = cache.get(ROUTER).download();
        assertFalse(cache.accept(connection, sample(SECOND, "FAILED", 0, 0)));
        cache.accept(connection, sample(new UUID(0, 0), "REJECTED", 0, 0));
        assertSame(original, cache.get(ROUTER).download());
        cache.accept(connection, sample(FIRST, "FINISHED", 0, 0));
        cache.markPending(connection, ROUTER);
        cache.accept(connection, sample(new UUID(0, 0), "REJECTED", 0, 0));
        assertEquals(FIRST, cache.get(ROUTER).payload().sessionId());
        assertSame(original, cache.get(ROUTER).download());
        cache.markPending(connection, ROUTER);
        cache.accept(connection, sample(SECOND, "PING", 0, 0));
        assertTrue(cache.get(ROUTER).download().isEmpty());
        assertFalse(cache.accept(connection, sample(FIRST, "UPLOAD", 20, 700)));
        assertTrue(cache.get(ROUTER).upload().isEmpty());
    }

    @Test
    void historyFollowsDeviceDimensionConnectionAndLruLifetime() {
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 10, 800));
        var nether = ClientSpeedtestState.routerKey(NETHER, POS);
        cache.accept(connection, packet(nether, SECOND, "UPLOAD"));
        assertTrue(cache.get(nether).download().isEmpty());
        assertTrue(cache.get(ROUTER).upload().isEmpty());
        assertFalse(cache.accept(new Object(), sample(FIRST, "DOWNLOAD", 20, 900)));
        assertEquals(1, cache.get(ROUTER).download().size());
        for (int i = 0; i < ClientSpeedtestState.MAX_ENTRIES; i++) {
            cache.get(ROUTER);
            var key = ClientSpeedtestState.routerKey(OVERWORLD, new BlockPos(i, 0, 0));
            cache.accept(connection, packet(key, new UUID(10, i), "DOWNLOAD"));
        }
        assertEquals(1, cache.get(ROUTER).download().size());
        assertTrue(cache.get(nether).upload().isEmpty());
        assertTrue(cache.get(ClientSpeedtestState.routerKey(OVERWORLD, BlockPos.ZERO)).download().isEmpty());
        cache.connected(new Object());
        assertTrue(cache.get(ROUTER).download().isEmpty());
    }

    @Test
    void bandwidthRiseIsMonotonicTimeBasedBoundedAndDropsToZeroImmediately() {
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 0, 1000));
        var snapshot = cache.get(ROUTER);
        double previous = 0;
        for (long nanos = 0; nanos <= 500_000_000L; nanos += 7_000_000L) {
            double current = snapshot.instantaneous(nanos);
            assertTrue(current >= previous);
            assertTrue(current <= 1000);
            previous = current;
        }
        assertEquals(500, snapshot.instantaneous(125_000_000L));
        assertEquals(1000, snapshot.instantaneous(250_000_000L));
        now.set(500_000_000L);
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 10, 400));
        assertEquals(400, cache.get(ROUTER).instantaneous(now.get()));
        now.addAndGet(100_000_000L);
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 12, 0));
        assertEquals(0, cache.get(ROUTER).instantaneous(now.get()));
        assertEquals(0, cache.get(ROUTER).instantaneous(now.get() + 500_000_000L));
        cache.accept(connection, sample(FIRST, "UPLOAD", 0, 200));
        assertEquals(0, cache.get(ROUTER).instantaneous(now.get()));
        assertEquals(200, cache.get(ROUTER).instantaneous(now.get() + 250_000_000L));
    }

    @Test
    void progressUsesThreeSecondPingAndThirtyThreeSecondTotalAndNeverFinishesLocally() {
        cache.accept(connection, sample(FIRST, "PING", 0, 0));
        var ping = cache.get(ROUTER);
        assertEquals(60, ping.phaseTicks());
        assertEquals(660, ping.totalTicks());
        assertEquals(60, ping.elapsedTicks(10_000_000_000L));
        assertEquals(9, ping.percent(10_000_000_000L));
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 0, 100));
        assertEquals(60, cache.get(ROUTER).elapsedTicks(now.get()));
        cache.accept(connection, sample(FIRST, "UPLOAD", 295, 100));
        var upload = cache.get(ROUTER);
        assertEquals(360 + 295, upload.elapsedTicks(now.get()));
        assertEquals(99, upload.percent(100_000_000_000L));
        assertTrue(upload.active());
        assertEquals("UPLOAD", upload.payload().state());
    }

    @Test
    void silenceFreezesTimeAtTwoCadencesOrThreeSecondsAndDuplicatesDoNotReviveIt() {
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 0, 100));
        var first = cache.get(ROUTER);
        assertFalse(first.waiting(2_999_999_999L));
        assertTrue(first.waiting(3_000_000_000L));
        assertEquals(first.elapsedTicks(3_000_000_000L), first.elapsedTicks(30_000_000_000L));
        now.set(1_000_000_000L);
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 20, 200));
        var second = cache.get(ROUTER);
        assertEquals(2_000_000_000L, second.staleAfter());
        assertFalse(second.waiting(2_999_999_999L));
        now.set(3_000_000_000L);
        assertTrue(second.waiting(now.get()));
        assertFalse(cache.accept(connection, sample(FIRST, "DOWNLOAD", 20, 200)));
        assertTrue(cache.get(ROUTER).waiting(now.get()));
        assertEquals(second.elapsedTicks(now.get()), second.elapsedTicks(99_000_000_000L));
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 21, 0));
        assertFalse(cache.get(ROUTER).waiting(now.get()));
        assertEquals(0, cache.get(ROUTER).instantaneous(now.get()));
    }

    @Test
    void pendingExpiryAndLegacyFinalNeverCreateSamples() {
        cache.accept(connection, sample(FIRST, "FINISHED", 0, 999));
        assertTrue(cache.get(ROUTER).download().isEmpty());
        cache.markPending(connection, ROUTER);
        now.set(ClientSpeedtestState.PENDING_TIMEOUT_NANOS);
        assertFalse(cache.get(ROUTER).pending());
        assertTrue(cache.get(ROUTER).download().isEmpty());
        assertTrue(cache.get(ROUTER).upload().isEmpty());
    }

    @Test
    void pingIsCappedByShortPhaseDurationToo() {
        cache.accept(connection, new SpeedtestUpdatePayload("ip", "PING", 15, 0, 0, 20,
                ROUTER.dimension(), ROUTER.deviceId(), FIRST, 0, 0));
        var snapshot = cache.get(ROUTER);
        assertEquals(20, snapshot.phaseTicks());
        assertEquals(60, snapshot.totalTicks());
        assertEquals(20, snapshot.elapsedTicks(10_000_000_000L));
        assertEquals(33, snapshot.percent(10_000_000_000L));
    }

    @Test
    void refusalErrorSurvivesReopeningWithoutReplacingConfirmedResultOrHistory() {
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 10, 800));
        cache.accept(connection, sample(FIRST, "FINISHED", 0, 0));
        var points = cache.get(ROUTER).download();
        cache.markPending(connection, ROUTER);
        cache.accept(connection, new SpeedtestUpdatePayload("ip", "REJECTED", 0, 0, 0, 300,
                ROUTER.dimension(), ROUTER.deviceId(), new UUID(0, 0), 0, 0, "chosen", "Chosen", "server_unavailable"));
        assertEquals("server_unavailable", cache.get(ROUTER).errorCode());
        assertEquals(FIRST, cache.get(ROUTER).payload().sessionId());
        assertSame(points, cache.get(ROUTER).download());
        assertFalse(cache.get(ROUTER).active());
    }

    @Test
    void failedUploadKeepsLastConfirmedTwentyEightSecondsWithoutAddingSamples() {
        cache.accept(connection, sample(FIRST, "DOWNLOAD", 0, 0));
        assertEquals(new ClientSpeedtestState.Point(0, 0), cache.get(ROUTER).download().getFirst());
        cache.accept(connection, sample(FIRST, "UPLOAD", 200, 400));
        var progress = cache.get(ROUTER);
        assertEquals(560, progress.confirmedElapsedTicks());
        assertEquals(28, progress.elapsedTicks(now.get()) / 20);
        now.set(2_000_000_000L);
        assertEquals(30, progress.elapsedTicks(now.get()) / 20);
        assertFalse(cache.accept(connection, sample(SECOND, "FAILED", 220, 0)));
        assertEquals(560, cache.get(ROUTER).confirmedElapsedTicks());
        assertTrue(cache.accept(connection, sample(FIRST, "FAILED", 220, 0)));
        var failed = cache.get(ROUTER);
        assertEquals(28, failed.elapsedTicks(now.get()) / 20);
        assertEquals(84, failed.percent(now.get()));
        assertSame(progress.download(), failed.download());
        assertSame(progress.upload(), failed.upload());
        assertEquals(0, failed.instantaneous(now.get()));
        now.set(100_000_000_000L);
        assertEquals(560, cache.get(ROUTER).elapsedTicks(now.get()));
        assertFalse(cache.get(ROUTER).active());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "REJECTED"})
    void firstTerminalPacketHasUnknownProgressEvenWhenItsTickCounterIsNonzero(String state) {
        assertTrue(cache.accept(connection, sample(FIRST, state, 200, 0)));
        var terminal = cache.get(ROUTER);
        assertEquals(-1, terminal.confirmedElapsedTicks());
        assertEquals(-1, terminal.elapsedTicks(now.get()));
        assertEquals(-1, terminal.percent(now.get()));
        assertTrue(terminal.download().isEmpty());
        assertTrue(terminal.upload().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "REJECTED"})
    void terminalForNewSessionCannotReuseOldConfirmedProgress(String state) {
        cache.accept(connection, sample(FIRST, "UPLOAD", 200, 400));
        cache.accept(connection, sample(FIRST, "FINISHED", 0, 0));
        assertEquals(100, cache.get(ROUTER).percent(now.get()));
        cache.markPending(connection, ROUTER);
        assertTrue(cache.accept(connection, sample(SECOND, state, 200, 0)));
        var terminal = cache.get(ROUTER);
        assertEquals(SECOND, terminal.payload().sessionId());
        assertEquals(-1, terminal.confirmedElapsedTicks());
        assertEquals(-1, terminal.percent(now.get()));
        assertTrue(terminal.upload().isEmpty());
        assertFalse(cache.accept(connection, sample(FIRST, "UPLOAD", 250, 500)));
        assertEquals(-1, cache.get(ROUTER).percent(now.get()));
    }

    @Test
    void refusalAndPendingTimeoutDoNotCreateConfirmedProgress() {
        cache.markPending(connection, ROUTER);
        now.set(ClientSpeedtestState.PENDING_TIMEOUT_NANOS);
        assertEquals(-1, cache.get(ROUTER).confirmedElapsedTicks());
        assertEquals("timeout", cache.get(ROUTER).errorCode());
        cache.accept(connection, sample(new UUID(0, 0), "REJECTED", 200, 0));
        assertEquals(-1, cache.get(ROUTER).percent(now.get()));
        assertTrue(cache.get(ROUTER).download().isEmpty());
    }

    private static SpeedtestUpdatePayload sample(UUID session, String state, int ticks, int actual) {
        return new SpeedtestUpdatePayload("192.168.0.2", state, 15, actual, ticks, 300,
                ROUTER.dimension(), ROUTER.deviceId(), session, 810, 230);
    }

    private static SpeedtestUpdatePayload packet(ClientSpeedtestState.Key key, UUID session, String state) {
        return new SpeedtestUpdatePayload("192.168.0.2", state, 15, 230, 40, 300,
                key.dimension(), key.deviceId(), session, 810, 230);
    }
}
