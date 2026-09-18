package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.client.ClientSpeedtestState;
import com.florentdubut.telecom.network.SpeedtestServerOption;
import com.florentdubut.telecom.network.packet.RouterGuiSyncPayload;
import com.florentdubut.telecom.network.packet.SpeedtestServersPayload;
import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import com.florentdubut.telecom.network.packet.StartSpeedtestPayload;
import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeedtestServerSelectionTest {
    private static final BlockPos POS = new BlockPos(3, 80, 4);
    private static final UUID PLAYER = new UUID(1, 2);
    private static final ClientSpeedtestState.Key KEY = ClientSpeedtestState.routerKey("minecraft:overworld", POS);
    private Minecraft minecraft;
    private Connection connection;
    private MockedStatic<Minecraft> singleton;

    @BeforeEach
    void setUp() {
        minecraft = mock(Minecraft.class);
        when(minecraft.getLastInputType()).thenReturn(net.minecraft.client.InputType.MOUSE);
        minecraft.level = mock(ClientLevel.class);
        minecraft.player = mock(LocalPlayer.class);
        when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
        when(minecraft.player.getUUID()).thenReturn(PLAYER);
        var listener = mock(ClientPacketListener.class);
        connection = mock(Connection.class);
        when(minecraft.getConnection()).thenReturn(listener);
        when(listener.getConnection()).thenReturn(connection);
        singleton = mockStatic(Minecraft.class);
        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
        ClientSpeedtestState.connected(connection);
    }

    @AfterEach
    void tearDown() {
        SmartphoneHUD.latestScan = null;
        ClientSpeedtestState.clear();
        singleton.close();
    }

    @Test
    void catalogueRejectsOldRequestConnectionDimensionAndDevice() throws Exception {
        var screen = selector(new AtomicReference<>());
        request(screen);
        UUID id = (UUID) field(screen, "requestId");
        var options = List.of(new SpeedtestServerOption("42", "Server", 20, true, 1000, ""));
        screen.receiveServers(connection, new SpeedtestServersPayload(UUID.randomUUID(), KEY.dimension(), KEY.deviceId(), options, false, ""));
        screen.receiveServers(connection, new SpeedtestServersPayload(id, "minecraft:the_nether", KEY.deviceId(), options, false, ""));
        screen.receiveServers(connection, new SpeedtestServersPayload(id, KEY.dimension(), "other", options, false, ""));
        screen.receiveServers(mock(Connection.class), new SpeedtestServersPayload(id, KEY.dimension(), KEY.deviceId(), options, false, ""));
        assertEquals(List.of(), field(screen, "servers"));
        screen.receiveServers(connection, new SpeedtestServersPayload(id, KEY.dimension(), KEY.deviceId(), options, true, ""));
        assertEquals(options, field(screen, "servers"));
        assertEquals(true, field(screen, "truncated"));
        var reopened = selector(new AtomicReference<>());
        request(reopened);
        reopened.receiveServers(connection, new SpeedtestServersPayload(id, KEY.dimension(), KEY.deviceId(), options, false, ""));
        assertEquals(List.of(), field(reopened, "servers"));
    }

    @Test
    void unavailableChoiceIsExplicitAndRefreshDoesNotSelectAuto() throws Exception {
        var chosen = new AtomicReference<SpeedtestServerOption>();
        var screen = selector(chosen);
        request(screen);
        screen.receiveServers(connection, new SpeedtestServersPayload((UUID) field(screen, "requestId"), KEY.dimension(), KEY.deviceId(), List.of(), false, ""));
        assertEquals("42", field(screen, "selectedId"));
        assertNull(chosen.get());
        var unavailable = new SpeedtestServerOption("42", "Offline", -1, false, 0, "server_unavailable");
        var select = SpeedtestServerSelectionScreen.class.getDeclaredMethod("select", SpeedtestServerOption.class);
        select.setAccessible(true);
        select.invoke(screen, unavailable);
        assertEquals(unavailable, chosen.get());
        verify(minecraft).setScreen((RouterScreen) field(screen, "parent"));
    }

    @Test
    void routerSyncWhileSelectingUpdatesSameParentWithoutChangingChoice() throws Exception {
        var screen = selector(new AtomicReference<>());
        RouterScreen parent = (RouterScreen) field(screen, "parent");
        set(parent, "selectedServerId", "42");
        screen.updateRouter(routerInfo(125));
        assertEquals("42", field(parent, "selectedServerId"));
        assertEquals(125, ((RouterGuiSyncPayload) field(parent, "payload")).lastPing());
        screen.onClose();
        verify(minecraft).setScreen(parent);
    }

    @Test
    void rejectionReasonRemainsVisibleAlongsideActiveTestOnBothParents() throws Exception {
        var phoneKey = ClientSpeedtestState.mobileKey(KEY.dimension(), PLAYER);
        var router = new RouterScreen(routerInfo(0));
        var phone = new SmartphoneSpeedtestScreen(null);
        var runningRouter = packet(KEY, "DOWNLOAD", "");
        var runningPhone = packet(phoneKey, "DOWNLOAD", "");
        ClientSpeedtestState.accept(connection, runningRouter);
        ClientSpeedtestState.accept(connection, runningPhone);
        var rejectedRouter = packet(KEY, "REJECTED", "device_busy");
        var rejectedPhone = packet(phoneKey, "REJECTED", "device_busy");
        ClientSpeedtestState.accept(connection, rejectedRouter);
        ClientSpeedtestState.accept(connection, rejectedPhone);
        router.updateSpeedtestProgress(rejectedRouter);
        phone.updateSpeedtestProgress(rejectedPhone);
        assertEquals("device_busy", field(router, "speedtestError"));
        assertEquals("device_busy", field(phone, "speedtestError"));
        assertSame(runningRouter, field(router, "currentSpeedtestData"));
        assertSame(runningPhone, field(phone, "currentSpeedtestData"));
        assertEquals(true, field(router, "speedtestActive"));
        assertEquals(true, field(phone, "speedtestActive"));
        router.updateSpeedtestProgress(runningRouter);
        phone.updateSpeedtestProgress(runningPhone);
        assertEquals("device_busy", field(router, "speedtestError"));
        assertEquals("device_busy", field(phone, "speedtestError"));
    }

    @Test
    void routerSelectorKeepsParentProximityCheck() {
        var screen = selector(new AtomicReference<>());
        when(minecraft.player.isWithinBlockInteractionRange(POS, 0)).thenReturn(false);
        screen.tick();
        verify(minecraft).setScreen(null);
    }

    @Test
    void bothStartButtonsSendCapturedDimensionEvenAfterClientChangesWorld() throws Exception {
        var router = new RouterScreen(routerInfo(0));
        var phone = new SmartphoneSpeedtestScreen(null);
        router.init();
        phone.init();
        set(router, "selectedServerId", "42");
        set(phone, "selectedServerId", "42");
        when(minecraft.level.dimension()).thenReturn(Level.NETHER);
        SmartphoneHUD.latestScan = new NetworkScanResponsePayload(true, "Antenna", -60, "4G", "10.0.0.2", POS.above(), 100, 50, 1);
        try (var packets = mockStatic(ClientPacketDistributor.class)) {
            for (Object screen : List.of(router, phone)) {
                Button button = (Button) field(screen, "startButton");
                var callback = Button.class.getDeclaredField("onPress");
                callback.setAccessible(true);
                ((Button.OnPress) callback.get(button)).onPress(button);
            }
            packets.verify(() -> ClientPacketDistributor.sendToServer(argThat(value -> value instanceof StartSpeedtestPayload payload
                    && payload.dimension().equals(KEY.dimension()) && payload.serverId().equals("42"))), times(2));
        }
    }

    @Test
    void allSpeedtestFailureAndCatalogueErrorsHaveFrenchAndEnglishTranslations() throws Exception {
        for (String locale : List.of("en_us", "fr_fr")) {
            try (var stream = getClass().getResourceAsStream("/assets/telecom/lang/" + locale + ".json")) {
                assertNotNull(stream);
                try (var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                    var translations = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                    for (String code : List.of("invalid_request", "device_busy", "session_limit", "server_unavailable", "no_server",
                            "timeout", "route_lost", "device_unavailable", "catalogue_limit", "radio_lost", "radio_unknown", "radio_limit")) {
                        String key = "gui.telecom.speedtest.error." + code;
                        assertTrue(translations.has(key), locale + ": " + key);
                        assertFalse(translations.get(key).getAsString().isBlank(), locale + ": " + key);
                        assertEquals(key, ((net.minecraft.network.chat.contents.TranslatableContents)
                                SpeedtestServerSelectionScreen.error(code).getContents()).getKey());
                    }
                }
            }
        }
    }

    private SpeedtestServerSelectionScreen selector(AtomicReference<SpeedtestServerOption> selected) {
        return new SpeedtestServerSelectionScreen(new RouterScreen(routerInfo(0)), false, POS, KEY, "42", selected::set);
    }

    private void request(SpeedtestServerSelectionScreen screen) throws Exception {
        try (var packets = mockStatic(ClientPacketDistributor.class)) {
            var method = SpeedtestServerSelectionScreen.class.getDeclaredMethod("requestServers");
            method.setAccessible(true);
            method.invoke(screen);
        }
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static RouterGuiSyncPayload routerInfo(int ping) {
        return new RouterGuiSyncPayload(POS, true, "192.168.0.2", 10, 1000, 900, 300, 100, 50, ping);
    }

    private static SpeedtestUpdatePayload packet(ClientSpeedtestState.Key key, String state, String error) {
        return new SpeedtestUpdatePayload("192.168.0.2", state, 20, 300, 10, 300, key.dimension(), key.deviceId(),
                state.equals("REJECTED") ? new UUID(0, 0) : UUID.randomUUID(), 100, 50, "42", "Server", error);
    }
}
