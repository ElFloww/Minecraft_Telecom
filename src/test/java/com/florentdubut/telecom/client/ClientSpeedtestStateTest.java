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
    void reopeningReadsProgressAndThenCompleteResultsWithoutPhaseHistory() {
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
        assertTrue(cache.markPending(connection, MOBILE));
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

    private static SpeedtestUpdatePayload packet(ClientSpeedtestState.Key key, UUID session, String state) {
        return new SpeedtestUpdatePayload("192.168.0.2", state, 15, 230, 40, 300,
                key.dimension(), key.deviceId(), session, 810, 230);
    }
}
