package com.florentdubut.telecom.client;

import com.florentdubut.telecom.client.gui.RouterScreen;
import com.florentdubut.telecom.client.gui.SmartphoneHUD;
import com.florentdubut.telecom.client.gui.SmartphoneSpeedtestScreen;
import com.florentdubut.telecom.network.packet.RouterGuiSyncPayload;
import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
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
import org.joml.Matrix3x2fStack;

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

    @Test
    void minimumResolutionControlsAndPanelsStayOnScreenAndNeverOverlap() throws Exception {
        for (Screen screen : new Screen[]{new RouterScreen(routerInfo(POS, 999)), new SmartphoneSpeedtestScreen(null)}) {
            screen.width = 320;
            screen.height = 240;
            init(screen);
            var buttons = screen.children().stream().filter(child -> child instanceof Button).map(child -> (Button) child).toList();
            int panelTop = screen instanceof RouterScreen ? 110 : 116;
            for (Button button : buttons) {
                assertTrue(button.getX() >= 0 && button.getY() >= 0);
                assertTrue(button.getRight() <= screen.width);
                assertTrue(button.getBottom() < panelTop);
                for (Button other : buttons) {
                    if (other == button) continue;
                    assertTrue(button.getRight() <= other.getX() || other.getRight() <= button.getX()
                            || button.getBottom() <= other.getY() || other.getBottom() <= button.getY());
                }
            }
        }
    }

    @Test
    void bothScreensRenderRealHistoryAtMinimumSizeWithoutRecordingFramesOrLegacyRefresh() throws Exception {
        var optionsField = Minecraft.class.getDeclaredField("options");
        optionsField.setAccessible(true);
        optionsField.set(minecraft, mock(net.minecraft.client.Options.class));
        var guiField = Minecraft.class.getDeclaredField("gui");
        guiField.setAccessible(true);
        guiField.set(minecraft, mock(net.minecraft.client.gui.Gui.class));
        for (var key : new ClientSpeedtestState.Key[]{ROUTER, MOBILE}) {
            for (int tick = 11875; tick < 12000; tick++) {
                ClientSpeedtestState.accept(new SpeedtestUpdatePayload("ip", "DOWNLOAD", 15, (tick - 11875) * 8, tick, 12000,
                        key.dimension(), key.deviceId(), FIRST, 400, 0));
            }
            var points = ClientSpeedtestState.get(key).download();
            Screen screen = key.equals(ROUTER) ? new RouterScreen(routerInfo(POS, 999)) : new SmartphoneSpeedtestScreen(null);
            screen.width = 320;
            screen.height = 240;
            var font = mock(Font.class);
            when(font.plainSubstrByWidth(anyString(), anyInt())).thenAnswer(i -> i.getArgument(0));
            var fontField = Screen.class.getDeclaredField("font");
            fontField.setAccessible(true);
            fontField.set(screen, font);
            var graphics = mock(GuiGraphics.class);
            when(graphics.pose()).thenReturn(new Matrix3x2fStack(8));
            for (int frame = 0; frame < 3; frame++) {
                if (screen instanceof RouterScreen router) {
                    router.updatePayload(routerInfo(POS, 888));
                    router.render(graphics, 0, 0, 0);
                } else {
                    screen.renderBackground(graphics, 0, 0, 0);
                }
            }
            assertSame(points, ClientSpeedtestState.get(key).download());
            assertEquals(120, points.size());
            int graphLeft = key.equals(ROUTER) ? 22 : 68;
            int graphRight = key.equals(ROUTER) ? 297 : 251;
            int graphTop = key.equals(ROUTER) ? 184 : 190;
            verify(graphics, atLeastOnce()).fill(graphLeft, 207, graphLeft + 1, 208, 0xFF00FFFF);
            verify(graphics, atLeastOnce()).fill(graphRight, graphTop, graphRight + 1, graphTop + 1, 0xFF00FFFF);
            assertEquals(new Matrix3x2fStack(8), graphics.pose());
            for (var invocation : mockingDetails(graphics).getInvocations()) {
                if (!invocation.getMethod().getName().equals("fill")) continue;
                Object[] coordinates = invocation.getArguments();
                assertTrue((int) coordinates[0] >= 0 && (int) coordinates[2] <= 320);
                assertTrue((int) coordinates[1] >= 0 && (int) coordinates[3] <= 240);
            }
            clearInvocations(graphics);
            screen.width = 640;
            screen.height = 480;
            if (screen instanceof RouterScreen router) router.render(graphics, 0, 0, 0);
            else screen.renderBackground(graphics, 0, 0, 0);
            int expandedRight = key.equals(ROUTER) ? 493 : 411;
            int expandedTop = key.equals(ROUTER) ? 248 : 254;
            verify(graphics, atLeastOnce()).fill(expandedRight, expandedTop, expandedRight + 1, expandedTop + 1, 0xFF00FFFF);
            assertTrue(383 - expandedTop > 100, "Larger windows should give small rate fluctuations more vertical space");
            assertSame(points, ClientSpeedtestState.get(key).download());
            for (var invocation : mockingDetails(graphics).getInvocations()) {
                if (!invocation.getMethod().getName().equals("fill")) continue;
                Object[] coordinates = invocation.getArguments();
                assertTrue((int) coordinates[0] >= 0 && (int) coordinates[2] <= 640);
                assertTrue((int) coordinates[1] >= 0 && (int) coordinates[3] <= 480);
            }
            Screen reopened = key.equals(ROUTER) ? new RouterScreen(routerInfo(POS, 999)) : new SmartphoneSpeedtestScreen(null);
            assertEquals(true, field(reopened, "speedtestActive"));
            assertSame(points, ClientSpeedtestState.get(key).download());
        }
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
