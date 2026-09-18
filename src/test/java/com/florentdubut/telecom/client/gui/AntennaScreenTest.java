package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.AntennaRadioConfig;
import com.florentdubut.telecom.network.TelecomFrequency;
import com.florentdubut.telecom.network.packet.AntennaGuiSyncPayload;
import com.florentdubut.telecom.network.packet.AntennaRefreshRequestPayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AntennaScreenTest {
    private MockedStatic<Minecraft> singleton;
    private AntennaScreen screen;

    @BeforeEach
    void setUp() throws Exception {
        singleton = mockStatic(Minecraft.class);
        Minecraft minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        when(minecraft.getLastInputType()).thenReturn(net.minecraft.client.InputType.MOUSE);
        var options = Minecraft.class.getDeclaredField("options");
        options.setAccessible(true);
        options.set(minecraft, mock(net.minecraft.client.Options.class, RETURNS_DEEP_STUBS));
        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
        screen = new AntennaScreen(new AntennaGuiSyncPayload(BlockPos.ZERO, "Original", 1, Map.of()));
        var font = Screen.class.getDeclaredField("font");
        font.setAccessible(true);
        Font mockedFont = mock(Font.class);
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
    void editsAllRadioFieldsAndPreservesDraftAcrossRefreshTabsAndResize() throws Exception {
        edit("name").setValue("Pending");
        press(button("omni"));
        edit("azimuth").setValue("359");
        edit("tilt").setValue("-15");
        edit("power").setValue("50");
        press(button("percent"));
        var expected = new AntennaRadioConfig(1, 359, -15, 50, 25);
        assertEquals(expected, screen.pendingConfig());
        var draft = screen.savePayload();

        screen.receiveUpdate(new AntennaGuiSyncPayload(BlockPos.ZERO, "Server", 0, Map.of(0, new int[]{1, 1}),
                new AntennaRadioConfig(3, 90, 45, 0, 50)));
        press(button("bands"));
        press(button("radio"));
        screen.width = 960;
        screen.height = 540;
        rebuild();
        assertEquals(draft, screen.savePayload());
        assertEquals("Pending", edit("name").getValue());
        assertEquals("359", edit("azimuth").getValue());
        assertEquals("-15", edit("tilt").getValue());
        assertEquals("50", edit("power").getValue());
    }

    @Test
    void saveButtonSendsDraftConfigAndPeriodicRefreshUsesOriginalPosition() throws Exception {
        press(button("omni"));
        edit("power").setValue("42");
        var expected = screen.savePayload();
        screen.receiveUpdate(new AntennaGuiSyncPayload(BlockPos.ZERO.east(), "Wrong", 0, Map.of()));
        try (var packets = mockStatic(ClientPacketDistributor.class)) {
            for (int tick = 0; tick < 40; tick++) screen.tick();
            packets.verify(() -> ClientPacketDistributor.sendToServer(new AntennaRefreshRequestPayload(BlockPos.ZERO, "minecraft:overworld", new java.util.UUID(0, 0))));
            press(button("save"));
            packets.verify(() -> ClientPacketDistributor.sendToServer(expected));
        }
    }

    @Test
    void openingExistingConfigurationInitializesDraftWithoutDefaults() {
        var config = new AntennaRadioConfig(2, 270, 12, 20, 50);
        var existing = new AntennaScreen(new AntennaGuiSyncPayload(BlockPos.ZERO, "Saved", 15, Map.of(), config));
        assertEquals(config, existing.pendingConfig());
        assertEquals("Saved", existing.savePayload().name());
        assertEquals(15, existing.savePayload().enabledFrequenciesMask());
    }

    @Test
    void rejectsInvalidNumericEditsWithoutClampingAndDisablesSave() {
        for (String[] invalid : new String[][]{{"azimuth", "360"}, {"azimuth", "-1"}, {"azimuth", ""},
                {"tilt", "-16"}, {"tilt", "46"}, {"power", "51"}, {"power", "-1"}, {"power", "1.5"}, {"power", "no"}}) {
            EditBox field = edit(invalid[0]);
            String previous = field.getValue();
            field.setValue(invalid[1]);
            assertNull(screen.savePayload());
            assertFalse(button("save").active);
            field.setValue(previous);
            assertNotNull(screen.savePayload());
            assertTrue(button("save").active);
        }
    }

    @Test
    void radioChoicesCycleOnlyThroughAllowedValues() throws Exception {
        for (int sectors : new int[]{1, 2, 3, 0}) {
            press(screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(value -> key(value.getMessage()).equals("omni") || key(value.getMessage()).equals("sector_count"))
                    .findFirst().orElseThrow());
            assertEquals(sectors, screen.pendingConfig().sectors());
        }
        for (int width : new int[]{25, 50, 100}) {
            press(button("percent"));
            assertEquals(width, screen.pendingConfig().bandwidthPercent());
        }
    }

    @Test
    void bandEditsArePreservedAndAllBandsAreReachableInSmallWindow() throws Exception {
        press(button("bands"));
        int seen = 0;
        do {
            List<Checkbox> boxes = screen.children().stream().filter(Checkbox.class::isInstance).map(Checkbox.class::cast).toList();
            seen += boxes.size();
            for (Checkbox box : boxes) {
                if (!box.selected()) {
                    // Checkbox uses its public onPress input hook in this Minecraft version.
                    var method = java.util.Arrays.stream(Checkbox.class.getMethods()).filter(m -> m.getName().equals("onPress"))
                            .findFirst().orElseThrow();
                    method.invoke(box, new Object[method.getParameterCount()]);
                }
            }
            assertWidgetBounds();
            Button next = literalButton(">");
            if (!next.active) break;
            press(next);
        } while (true);
        assertEquals(TelecomFrequency.values().length, seen);
        assertEquals((1 << seen) - 1, screen.savePayload().enabledFrequenciesMask());
        press(button("radio"));
        assertEquals((1 << seen) - 1, screen.savePayload().enabledFrequenciesMask());
    }

    @Test
    void liveUsesRefreshedConfigAndUsageButRejectsOtherPosition() throws Exception {
        screen.receiveUpdate(new AntennaGuiSyncPayload(BlockPos.ZERO, "Server", 1 << 4, Map.of(4, new int[]{7, 9999}),
                new AntennaRadioConfig(3, 90, 45, 0, 50)));
        screen.receiveUpdate(new AntennaGuiSyncPayload(BlockPos.ZERO.east(), "Wrong", 0, Map.of()));
        press(button("live"));
        press(literalButton(">"));
        GuiGraphics graphics = mock(GuiGraphics.class, RETURNS_DEEP_STUBS);
        screen.render(graphics, 0, 0, 0);
        var components = ArgumentCaptor.forClass(Component.class);
        verify(graphics, atLeastOnce()).drawString(any(Font.class), components.capture(), anyInt(), anyInt(), anyInt());
        var usage = components.getAllValues().stream().filter(component -> key(component).equals("usage"))
                .map(component -> ((TranslatableContents) component.getContents()).getArgs()).findFirst().orElseThrow();
        assertArrayEquals(new Object[]{7, 37}, usage);
        assertEquals(AntennaRadioConfig.DEFAULT, screen.pendingConfig());
        assertEquals(BlockPos.ZERO, screen.savePayload().pos());
    }

    @Test
    void pendingCapacityPreviewAndReferenceWidthsFollowBandAndConfiguration() throws Exception {
        press(button("percent"));
        press(button("bands"));
        press(literalButton(">"));
        GuiGraphics graphics = mock(GuiGraphics.class, RETURNS_DEEP_STUBS);
        screen.render(graphics, 0, 0, 0);
        var components = ArgumentCaptor.forClass(Component.class);
        verify(graphics, atLeastOnce()).drawString(any(Font.class), components.capture(), anyInt(), anyInt(), anyInt());
        assertTrue(components.getAllValues().stream().filter(c -> key(c).equals("nominal"))
                .anyMatch(c -> ((TranslatableContents) c.getContents()).getArgs()[0].equals(18)));
        assertEquals(0.2, AntennaScreen.referenceWidthMhz(TelecomFrequency.G2_900));
        assertEquals(5, AntennaScreen.referenceWidthMhz(TelecomFrequency.G3_900));
        assertEquals(20, AntennaScreen.referenceWidthMhz(TelecomFrequency.G4_700));
        assertEquals(100, AntennaScreen.referenceWidthMhz(TelecomFrequency.G5_3500));
        assertEquals(400, AntennaScreen.referenceWidthMhz(TelecomFrequency.G5_26000));
    }

    @Test
    void controlsFitDesktopAndSmallWindowAndTranslationsMatch() throws Exception {
        for (int[] size : new int[][]{{320, 240}, {960, 540}}) {
            screen.width = size[0];
            screen.height = size[1];
            rebuild();
            assertWidgetBounds();
            press(button("bands"));
            assertWidgetBounds();
            press(button("live"));
            assertWidgetBounds();
            press(button("radio"));
        }
        JsonObject english = language("en_us"), french = language("fr_fr");
        for (String key : english.keySet()) {
            if (!key.startsWith("gui.telecom.antenna.")) continue;
            assertTrue(french.has(key), key);
            assertFalse(french.get(key).getAsString().isBlank(), key);
            assertEquals(english.get(key).getAsString().chars().filter(c -> c == '%').count(),
                    french.get(key).getAsString().chars().filter(c -> c == '%').count(), key);
        }
        assertTrue(english.get("gui.telecom.antenna.sectors_hint").getAsString().contains("share"));
        assertTrue(french.get("gui.telecom.antenna.sectors_hint").getAsString().contains("partagent"));
    }

    @Test
    void radioFailureTranslationsMatchScreenKeysAndDistinguishLimitsFromUnknownTerrain() throws Exception {
        for (String locale : List.of("en_us", "fr_fr")) {
            JsonObject translations = language(locale);
            for (String code : List.of("radio_lost", "radio_unknown", "radio_limit")) {
                String key = "gui.telecom.speedtest.error." + code;
                assertEquals(key, ((TranslatableContents) SpeedtestServerSelectionScreen.error(code).getContents()).getKey());
                assertTrue(translations.has(key), locale + ": " + key);
                assertFalse(translations.get(key).getAsString().isBlank(), locale + ": " + key);
            }
            String limit = translations.get("gui.telecom.speedtest.error.radio_limit").getAsString();
            assertEquals(limit, translations.get("message.telecom.radio_limit").getAsString());
            assertTrue(limit.contains(locale.equals("en_us") ? "reduce nearby antennas or the network size"
                    : "réduire le nombre d'antennes proches ou la taille du réseau"));
            String unknown = translations.get("gui.telecom.speedtest.error.radio_unknown").getAsString();
            assertTrue(unknown.contains(locale.equals("en_us") ? "terrain data unavailable" : "terrain indisponibles"));
            assertNotEquals(limit, unknown);
            assertNotEquals(limit, translations.get("message.telecom.no_service").getAsString());
        }
    }

    private void assertWidgetBounds() {
        for (var child : screen.children()) {
            var widget = (AbstractWidget) child;
            assertTrue(widget.getX() >= 0 && widget.getY() >= 0);
            assertTrue(widget.getX() + widget.getWidth() <= screen.width);
            assertTrue(widget.getY() + widget.getHeight() <= screen.height);
        }
    }

    private void rebuild() throws Exception {
        var method = Screen.class.getDeclaredMethod("rebuildWidgets");
        method.setAccessible(true);
        method.invoke(screen);
    }

    private EditBox edit(String key) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .filter(box -> key(box.getMessage()).equals(key)).findFirst().orElseThrow();
    }

    private Button button(String key) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> key(button.getMessage()).equals(key)).findFirst().orElseThrow();
    }

    private Button literalButton(String label) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }

    private static void press(Button button) throws Exception {
        assertTrue(button.active);
        var callback = Button.class.getDeclaredField("onPress");
        callback.setAccessible(true);
        ((Button.OnPress) callback.get(button)).onPress(button);
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents contents
                ? contents.getKey().replace("gui.telecom.antenna.", "") : "";
    }

    private static JsonObject language(String name) throws Exception {
        try (var stream = AntennaScreenTest.class.getResourceAsStream("/assets/telecom/lang/" + name + ".json")) {
            assertNotNull(stream);
            try (var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
}
