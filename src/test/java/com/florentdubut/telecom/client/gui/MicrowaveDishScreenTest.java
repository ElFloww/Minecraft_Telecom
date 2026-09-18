package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.MicrowaveConfig;
import com.florentdubut.telecom.network.packet.MicrowaveGuiSyncPayload;
import com.florentdubut.telecom.network.packet.MicrowaveRefreshRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MicrowaveDishScreenTest {
    private static final BlockPos POS = new BlockPos(10, 64, 10);
    private static final UUID VIEW = new UUID(1, 2);
    private MockedStatic<Minecraft> singleton;
    private MicrowaveDishScreen screen;

    @BeforeEach
    void setUp() throws Exception {
        singleton = mockStatic(Minecraft.class);
        var minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        when(minecraft.getLastInputType()).thenReturn(net.minecraft.client.InputType.MOUSE);
        var options = Minecraft.class.getDeclaredField("options");
        options.setAccessible(true);
        options.set(minecraft, mock(net.minecraft.client.Options.class, RETURNS_DEEP_STUBS));
        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
        screen = new MicrowaveDishScreen(packet("disabled", true, VIEW, MicrowaveConfig.DEFAULT, null));
        var font = Screen.class.getDeclaredField("font");
        font.setAccessible(true);
        var mockedFont = mock(Font.class);
        when(mockedFont.plainSubstrByWidth(anyString(), anyInt())).thenAnswer(call -> call.getArgument(0));
        when(mockedFont.plainSubstrByWidth(anyString(), anyInt(), anyBoolean())).thenAnswer(call -> call.getArgument(0));
        font.set(screen, mockedFont);
        screen.width = 320;
        screen.height = 240;
        screen.init();
    }

    @AfterEach
    void tearDown() { singleton.close(); }

    @Test
    void pairingAimAndEveryRadioFieldRemainDraftUntilSaved() throws Exception {
        edit("name").setValue("Pending");
        setPeer(POS.west(100).above(100));
        try (var packets = mockStatic(ClientPacketDistributor.class)) {
            press("aim");
            assertEquals(90, screen.pendingConfig().azimuthDegrees());
            assertEquals(45, screen.pendingConfig().elevationDegrees());
            packets.verifyNoInteractions();
        }
        press("radio");
        assertEquals("90", edit("azimuth").getValue());
        assertEquals("45", edit("elevation").getValue());
        press("disabled");
        edit("channel").setValue("16");
        press("ghz");
        edit("azimuth").setValue("359");
        edit("elevation").setValue("-90");
        var expected = new MicrowaveConfig(POS.west(100).above(100), 16, 18, 359, -90, true);
        assertEquals(expected, screen.pendingConfig());
        var save = screen.savePayload();
        screen.receiveUpdate(packet("pending", false, VIEW, MicrowaveConfig.DEFAULT, null));
        press("status");
        press("pairing");
        screen.width = 960;
        screen.height = 540;
        rebuild();
        assertEquals(save, screen.savePayload());
        assertEquals("Pending", edit("name").getValue());
        try (var packets = mockStatic(ClientPacketDistributor.class)) {
            press("save");
            packets.verify(() -> ClientPacketDistributor.sendToServer(save));
        }
    }

    @Test
    void aimMatchesMinecraftYawAllDirectionsAndElevationSign() throws Exception {
        BlockPos[] targets = {POS.south(100), POS.west(100), POS.north(100), POS.east(100), POS.above(100), POS.below(100)};
        int[] azimuth = {0, 90, 180, 270, 0, 0}, elevation = {0, 0, 0, 0, 90, -90};
        for (int i = 0; i < targets.length; i++) {
            setPeer(targets[i]);
            press("aim");
            assertEquals(azimuth[i], screen.pendingConfig().azimuthDegrees());
            assertEquals(elevation[i], screen.pendingConfig().elevationDegrees());
        }
    }

    @Test
    void malformedPartialSelfAndDistantPeerDisableSaveAndAimAndClearRestoresUnpaired() throws Exception {
        assertFalse(button("aim").active);
        edit("target_x").setValue("20");
        assertNull(screen.savePayload());
        assertFalse(button("save").active);
        assertFalse(button("aim").active);
        for (BlockPos bad : new BlockPos[]{POS, POS.south(4097), new BlockPos(30_000_000, 64, 10), new BlockPos(10, 2048, 10)}) {
            setPeer(bad);
            assertNull(screen.savePayload());
            assertFalse(button("aim").active);
        }
        setPeer(POS.south(100));
        assertTrue(button("save").active);
        assertTrue(button("aim").active);
        edit("target_y").setValue("1.5");
        assertNull(screen.savePayload());
        press("clear_peer");
        assertEquals(MicrowaveConfig.DEFAULT, screen.pendingConfig());
        assertTrue(button("save").active);
    }

    @Test
    void currentDimensionHeightDisablesSaveAndAimAndShowsInvalidFeedback() throws Exception {
        var client = Minecraft.getInstance();
        var clientField = Screen.class.getDeclaredField("minecraft");
        clientField.setAccessible(true);
        clientField.set(screen, client);
        assertNull(client.level);
        setPeer(new BlockPos(20, 400, 10));
        assertNotNull(screen.savePayload());

        client.level = mock(ClientLevel.class);
        when(client.level.getMinY()).thenReturn(-64);
        when(client.level.getMaxY()).thenReturn(319);
        for (int y : new int[]{400, 320, -65}) {
            setPeer(new BlockPos(20, y, 10));
            assertNull(screen.savePayload());
            assertFalse(button("save").active);
            assertFalse(button("aim").active);
        }
        var graphics = mock(GuiGraphics.class, RETURNS_DEEP_STUBS);
        screen.render(graphics, 0, 0, 0);
        var components = ArgumentCaptor.forClass(Component.class);
        verify(graphics, atLeastOnce()).drawString(any(Font.class), components.capture(), anyInt(), anyInt(), anyInt());
        assertTrue(components.getAllValues().stream().anyMatch(c -> key(c).equals("invalid")));
        for (int y : new int[]{-64, 319}) {
            setPeer(new BlockPos(20, y, 10));
            assertNotNull(screen.savePayload());
            assertTrue(button("save").active);
            assertTrue(button("aim").active);
        }
        when(client.level.getMinY()).thenReturn(0);
        when(client.level.getMaxY()).thenReturn(255);
        setPeer(new BlockPos(20, 319, 10));
        assertNull(screen.savePayload());
        assertFalse(button("save").active);
        assertFalse(button("aim").active);
        setPeer(new BlockPos(20, 255, 10));
        assertTrue(button("save").active);
        assertTrue(button("aim").active);
        press("clear_peer");
        assertNotNull(screen.savePayload());
        assertTrue(button("save").active);
        assertFalse(button("aim").active);
        verify(client.level, never()).getChunk(anyInt(), anyInt());
    }

    @Test
    void radioBoundsRejectRatherThanClampAndFrequencyCyclesOnlyDiscreteChoices() throws Exception {
        press("radio");
        for (String[] invalid : new String[][]{{"channel", "0"}, {"channel", "17"}, {"channel", ""}, {"azimuth", "-1"},
                {"azimuth", "360"}, {"elevation", "-91"}, {"elevation", "91"}, {"elevation", "1.5"}}) {
            var edit = edit(invalid[0]);
            String previous = edit.getValue();
            edit.setValue(invalid[1]);
            assertNull(screen.savePayload());
            assertFalse(button("save").active);
            edit.setValue(previous);
            assertTrue(button("save").active);
        }
        for (int frequency : new int[]{18, 38, 6, 11}) {
            press("ghz");
            assertEquals(frequency, screen.pendingConfig().frequencyGhz());
        }
    }

    @Test
    void allTabsFitSmallAndDesktopWithoutWidgetOverlap() throws Exception {
        for (int[] size : new int[][]{{320, 240}, {960, 540}}) {
            screen.width = size[0]; screen.height = size[1]; rebuild();
            assertBounds();
            press("radio"); assertBounds();
            press("status"); assertBounds();
            press("pairing"); assertBounds();
        }
    }

    @Test
    void authoritativeStatusShowsReasonCapacityLatencyAndCompleteBlockerCoordinates() throws Exception {
        BlockPos blocker = new BlockPos(-29999999, -64, 29999999);
        var config = new MicrowaveConfig(POS.south(100), 1, 11, 0, 0, true);
        screen.receiveUpdate(packet("fresnel_blocked", false, VIEW, config, blocker));
        // Another view cannot replace the accepted telemetry or draft.
        screen.receiveUpdate(packet("ready", false, UUID.randomUUID(), config, null));
        press("status");
        var graphics = mock(GuiGraphics.class, RETURNS_DEEP_STUBS);
        screen.render(graphics, 0, 0, 0);
        var capture = ArgumentCaptor.forClass(Component.class);
        verify(graphics, atLeastOnce()).drawString(any(Font.class), capture.capture(), anyInt(), anyInt(), anyInt());
        var components = capture.getAllValues();
        assertTrue(components.stream().anyMatch(c -> key(c).equals("state.fresnel_blocked")));
        assertFalse(components.stream().anyMatch(c -> key(c).equals("state.ready")));
        var capacity = components.stream().filter(c -> key(c).equals("capacity")).findFirst().orElseThrow();
        assertArrayEquals(new Object[]{0, 600}, ((TranslatableContents) capacity.getContents()).getArgs());
        assertTrue(components.stream().anyMatch(c -> key(c).equals("latency")));
        assertTrue(components.stream().anyMatch(c -> c.getString().equals("-29999999, -64, 29999999")));
        assertEquals(MicrowaveConfig.DEFAULT, screen.pendingConfig());
    }

    @Test
    void periodicRefreshUsesOpeningDimensionPositionAndView() {
        try (var packets = mockStatic(ClientPacketDistributor.class)) {
            for (int i = 0; i < 40; i++) screen.tick();
            packets.verify(() -> ClientPacketDistributor.sendToServer(new MicrowaveRefreshRequestPayload(POS, "minecraft:overworld", VIEW)));
        }
    }

    private void setPeer(BlockPos peer) {
        edit("target_x").setValue(Integer.toString(peer.getX()));
        edit("target_y").setValue(Integer.toString(peer.getY()));
        edit("target_z").setValue(Integer.toString(peer.getZ()));
    }

    private void assertBounds() {
        var widgets = screen.children().stream().map(AbstractWidget.class::cast).toList();
        for (var a : widgets) {
            assertTrue(a.getX() >= 0 && a.getY() >= 0);
            assertTrue(a.getRight() <= screen.width && a.getBottom() <= screen.height);
            for (var b : widgets) {
                if (a == b) continue;
                assertTrue(a.getRight() <= b.getX() || b.getRight() <= a.getX() || a.getBottom() <= b.getY() || b.getBottom() <= a.getY());
            }
        }
    }

    private EditBox edit(String key) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .filter(box -> key(box.getMessage()).equals(key)).findFirst().orElseThrow();
    }

    private Button button(String key) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> key(button.getMessage()).equals(key)).findFirst().orElseThrow();
    }

    private void press(String key) throws Exception {
        var button = button(key);
        assertTrue(button.active, key);
        var callback = Button.class.getDeclaredField("onPress");
        callback.setAccessible(true);
        ((Button.OnPress) callback.get(button)).onPress(button);
    }

    private void rebuild() throws Exception {
        var method = Screen.class.getDeclaredMethod("rebuildWidgets");
        method.setAccessible(true);
        method.invoke(screen);
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents contents
                ? contents.getKey().replace("gui.telecom.microwave.", "") : "";
    }

    private static MicrowaveGuiSyncPayload packet(String state, boolean opening, UUID view, MicrowaveConfig config, BlockPos blocker) {
        return new MicrowaveGuiSyncPayload(POS, "Site", config, "minecraft:overworld", view, opening,
                config.peer() == null ? POS : config.peer(), state, 0, 600, 2, blocker);
    }
}
