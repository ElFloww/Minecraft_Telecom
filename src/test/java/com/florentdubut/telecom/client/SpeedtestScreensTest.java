package com.florentdubut.telecom.client;

import com.florentdubut.telecom.client.gui.RouterScreen;
import com.florentdubut.telecom.client.gui.SmartphoneHUD;
import com.florentdubut.telecom.client.gui.SmartphoneSpeedtestScreen;
import com.florentdubut.telecom.network.packet.RouterGuiSyncPayload;
import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeedtestScreensTest {
    private static final BlockPos POS = new BlockPos(3, 80, 4);
    private static final UUID PLAYER = new UUID(1, 2);
    private static final UUID FIRST = new UUID(2, 1);
    private static final UUID SECOND = new UUID(2, 2);
    private static final ClientSpeedtestState.Key ROUTER = ClientSpeedtestState.routerKey("minecraft:overworld", POS);
    private static final ClientSpeedtestState.Key MOBILE = ClientSpeedtestState.mobileKey("minecraft:overworld", PLAYER);
    private MockedStatic<Minecraft> singleton;
    private Minecraft minecraft;

    @BeforeEach
    void setUp() {
        minecraft = mock(Minecraft.class);
        minecraft.level = mock(ClientLevel.class);
        minecraft.player = mock(LocalPlayer.class);
        when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
        when(minecraft.player.getUUID()).thenReturn(PLAYER);
        var listener = mock(ClientPacketListener.class);
        var connection = mock(Connection.class);
        when(minecraft.getConnection()).thenReturn(listener);
        when(listener.getConnection()).thenReturn(connection);
        singleton = mockStatic(Minecraft.class);
        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
        ClientSpeedtestState.connected(connection);
        SmartphoneHUD.latestScan = null;
    }

    @AfterEach
    void tearDown() {
        ClientSpeedtestState.clear();
        singleton.close();
    }

    @Test
    void queuedPacketFromAnotherConnectionCannotEnterTheCurrentCache() {
        var oldConnection = mock(Connection.class);
        var payload = packet(ROUTER, FIRST, "DOWNLOAD");
        assertFalse(ClientSpeedtestState.accept(oldConnection, payload));
        assertNull(ClientSpeedtestState.get(ROUTER).payload());
        assertTrue(ClientSpeedtestState.accept(minecraft.getConnection().getConnection(), payload));
        assertEquals(payload, ClientSpeedtestState.get(ROUTER).payload());
    }

    @Test
    void routerReopensActiveTestAndRefreshCannotOverwriteIt() throws Exception {
        var progress = packet(ROUTER, FIRST, "UPLOAD");
        ClientSpeedtestState.accept(progress);
        RouterScreen screen = new RouterScreen(routerInfo(POS, 999));
        assertSame(progress, field(screen, "currentSpeedtestData"));
        assertEquals(true, field(screen, "speedtestActive"));
        assertEquals(810, field(screen, "lastDownBw"));
        assertEquals(230, field(screen, "lastUpBw"));

        screen.updatePayload(routerInfo(POS, 777));
        assertSame(progress, field(screen, "currentSpeedtestData"));
        assertEquals(15, ((SpeedtestUpdatePayload) field(screen, "currentSpeedtestData")).pingMs());
        assertEquals(810, field(screen, "lastDownBw"));

        var finished = packet(ROUTER, FIRST, "FINISHED");
        ClientSpeedtestState.accept(finished);
        RouterScreen reopened = new RouterScreen(routerInfo(POS, 999));
        assertSame(finished, field(reopened, "currentSpeedtestData"));
        assertEquals(false, field(reopened, "speedtestActive"));
        assertEquals(230, field(reopened, "lastUpBw"));
    }

    @Test
    void persistedRouterResultsDoNotBecomeFakePacketsOrMobileResults() throws Exception {
        RouterScreen router = new RouterScreen(routerInfo(POS, 999));
        assertNull(field(router, "currentSpeedtestData"));
        assertEquals(100, field(router, "lastDownBw"));
        assertEquals(50, field(router, "lastUpBw"));
        assertNull(ClientSpeedtestState.get(ROUTER).payload());
        SmartphoneSpeedtestScreen mobile = new SmartphoneSpeedtestScreen(null);
        assertNull(field(mobile, "currentSpeedtestData"));
        assertEquals(0, field(mobile, "lastDownBw"));
    }

    @Test
    void mobileMatchesPlayerWithoutScanAndNeverRouterIp() throws Exception {
        var routerResult = packet(ROUTER, FIRST, "FINISHED");
        ClientSpeedtestState.accept(routerResult);
        SmartphoneSpeedtestScreen screen = new SmartphoneSpeedtestScreen(null);
        screen.updateSpeedtestProgress(routerResult);
        assertNull(field(screen, "currentSpeedtestData"));

        var mobileProgress = packet(MOBILE, SECOND, "UPLOAD");
        ClientSpeedtestState.accept(mobileProgress);
        screen.updateSpeedtestProgress(mobileProgress);
        assertSame(mobileProgress, field(screen, "currentSpeedtestData"));
        assertEquals(true, field(screen, "speedtestActive"));
        assertEquals(810, field(screen, "lastDownBw"));
        assertEquals(230, field(screen, "lastUpBw"));
        screen.updateSpeedtestProgress(routerResult);
        assertSame(mobileProgress, field(screen, "currentSpeedtestData"));

        var result = packet(MOBILE, SECOND, "FINISHED");
        ClientSpeedtestState.accept(result);
        SmartphoneSpeedtestScreen reopened = new SmartphoneSpeedtestScreen(null);
        assertSame(result, field(reopened, "currentSpeedtestData"));
        assertEquals(false, field(reopened, "speedtestActive"));
        assertEquals(810, field(reopened, "lastDownBw"));
        assertEquals(230, field(reopened, "lastUpBw"));
    }

    @Test
    void routerIgnoresOtherPositionEvenWithSameIp() throws Exception {
        var progress = packet(ROUTER, FIRST, "DOWNLOAD");
        ClientSpeedtestState.accept(progress);
        RouterScreen screen = new RouterScreen(routerInfo(POS, 999));
        var other = packet(ClientSpeedtestState.routerKey(ROUTER.dimension(), POS.above()), SECOND, "FINISHED");
        ClientSpeedtestState.accept(other);
        screen.updateSpeedtestProgress(other);
        screen.updatePayload(routerInfo(POS.above(), 777));
        assertSame(progress, field(screen, "currentSpeedtestData"));
        assertEquals(true, field(screen, "speedtestActive"));
        assertEquals(POS, ((RouterGuiSyncPayload) field(screen, "payload")).pos());
    }

    @Test
    void screenDispatchCannotBypassSessionGuardOrCancelActiveOnRejection() throws Exception {
        ClientSpeedtestState.accept(packet(MOBILE, FIRST, "DOWNLOAD"));
        var newer = packet(MOBILE, SECOND, "PING");
        ClientSpeedtestState.accept(newer);
        SmartphoneSpeedtestScreen screen = new SmartphoneSpeedtestScreen(null);
        var stale = packet(MOBILE, FIRST, "FINISHED");
        assertFalse(ClientSpeedtestState.accept(stale));
        screen.updateSpeedtestProgress(stale);
        assertSame(newer, field(screen, "currentSpeedtestData"));

        var rejected = packet(MOBILE, new UUID(0, 0), "REJECTED");
        ClientSpeedtestState.accept(rejected);
        screen.updateSpeedtestProgress(rejected);
        assertSame(newer, field(screen, "currentSpeedtestData"));
        assertEquals(true, field(screen, "speedtestActive"));

        var failed = packet(MOBILE, SECOND, "FAILED");
        ClientSpeedtestState.accept(failed);
        screen.updateSpeedtestProgress(failed);
        assertSame(failed, field(screen, "currentSpeedtestData"));
        assertEquals(false, field(screen, "speedtestActive"));
    }

    @Test
    void newDimensionScreensDoNotShowPreviousWorldResults() throws Exception {
        var oldRouter = packet(ROUTER, FIRST, "DOWNLOAD");
        var oldMobile = packet(MOBILE, SECOND, "UPLOAD");
        ClientSpeedtestState.accept(oldRouter);
        ClientSpeedtestState.accept(oldMobile);
        when(minecraft.level.dimension()).thenReturn(Level.NETHER);
        var router = new RouterScreen(routerInfo(POS, 0));
        var mobile = new SmartphoneSpeedtestScreen(null);
        router.updateSpeedtestProgress(oldRouter);
        mobile.updateSpeedtestProgress(oldMobile);
        assertNull(field(router, "currentSpeedtestData"));
        assertNull(field(mobile, "currentSpeedtestData"));
        assertEquals(false, field(router, "speedtestActive"));
        assertEquals(false, field(mobile, "speedtestActive"));
        assertSame(oldRouter, ClientSpeedtestState.get(ROUTER).payload());
    }

    @Test
    void logoutEventClearsPendingAndResultsAndLatePayloadIsIgnored() throws Exception {
        var oldRouter = packet(ROUTER, FIRST, "DOWNLOAD");
        ClientSpeedtestState.accept(oldRouter);
        ClientSpeedtestState.markPending(MOBILE);
        ClientEvents.onLogout(mock(ClientPlayerNetworkEvent.LoggingOut.class));
        assertFalse(ClientSpeedtestState.accept(oldRouter));
        assertFalse(ClientSpeedtestState.get(MOBILE).pending());
        assertNull(field(new RouterScreen(routerInfo(POS, 0)), "currentSpeedtestData"));
        assertNull(field(new SmartphoneSpeedtestScreen(null), "currentSpeedtestData"));
    }

    @Test
    void pendingSurvivesReopeningAndRejectionUnlocksOnlyItsDeviceButton() throws Exception {
        ClientSpeedtestState.markPending(ROUTER);
        ClientSpeedtestState.markPending(MOBILE);
        var router = new RouterScreen(routerInfo(POS, 999));
        var mobile = new SmartphoneSpeedtestScreen(null);
        init(router);
        init(mobile);
        assertFalse(((Button) field(router, "startButton")).active);
        assertFalse(((Button) field(mobile, "startButton")).active);
        assertNull(field(router, "currentSpeedtestData"));

        var rejected = packet(ROUTER, new UUID(0, 0), "REJECTED");
        ClientSpeedtestState.accept(rejected);
        router.updateSpeedtestProgress(rejected);
        mobile.updateSpeedtestProgress(rejected);
        assertTrue(((Button) field(router, "startButton")).active);
        assertFalse(((Button) field(mobile, "startButton")).active);
        assertEquals(false, field(router, "speedtestActive"));
    }

    @Test
    void closingScreensDoesNotCancelTests() {
        var routerProgress = packet(ROUTER, FIRST, "DOWNLOAD");
        var mobileProgress = packet(MOBILE, SECOND, "UPLOAD");
        ClientSpeedtestState.accept(routerProgress);
        ClientSpeedtestState.accept(mobileProgress);
        new RouterScreen(routerInfo(POS, 999)).onClose();
        new SmartphoneSpeedtestScreen(null).onClose();
        assertSame(routerProgress, ClientSpeedtestState.get(ROUTER).payload());
        assertSame(mobileProgress, ClientSpeedtestState.get(MOBILE).payload());
        assertTrue(ClientSpeedtestState.get(ROUTER).active());
        assertTrue(ClientSpeedtestState.get(MOBILE).active());
    }

    private static void init(Object screen) throws ReflectiveOperationException {
        var init = screen.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(screen);
    }

    private static Object field(Object screen, String name) throws ReflectiveOperationException {
        var field = screen.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(screen);
    }

    private static RouterGuiSyncPayload routerInfo(BlockPos pos, int lastPing) {
        return new RouterGuiSyncPayload(pos, true, "192.168.0.2", 10, 1000, 900, 300, 100, 50, lastPing);
    }

    private static SpeedtestUpdatePayload packet(ClientSpeedtestState.Key key, UUID session, String state) {
        return new SpeedtestUpdatePayload("192.168.0.2", state, 15, 230, 40, 300,
                key.dimension(), key.deviceId(), session, 810, 230);
    }
}
