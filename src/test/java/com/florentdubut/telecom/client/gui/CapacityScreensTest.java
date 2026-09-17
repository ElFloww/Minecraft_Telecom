package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.NetworkToolSyncPayload;
import com.florentdubut.telecom.network.packet.NetworkToolSyncPayload.CapacityMode;
import com.florentdubut.telecom.network.packet.ServerGuiSyncPayload;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapacityScreensTest {
    @Test
    void routerBarsUseDistinctDownAndUpMaxima() {
        try (var singleton = mockStatic(Minecraft.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            var screen = new NetworkToolScreen(new NetworkToolSyncPayload(BlockPos.ZERO, "gui.telecom.tool.node.router",
                    0, 1000, 700, 500, 350, CapacityMode.DIRECTIONAL));
            screen.width = 320;
            screen.height = 240;
            var graphics = mock(GuiGraphics.class);
            screen.render(graphics, 0, 0, 0);
            var rates = rates(graphics);
            assertEquals(2, rates.size());
            assertArrayEquals(new Object[]{500L, 1000}, new Object[]{rates.get(0)[1], rates.get(0)[2]});
            assertArrayEquals(new Object[]{350L, 700}, new Object[]{rates.get(1)[1], rates.get(1)[2]});
            verify(graphics).fill(15, 124, 160, 129, 0xFF00FFFF);
            verify(graphics).fill(15, 164, 160, 169, 0xFFFF8800);
        }
    }

    @Test
    void cableShowsOneCombinedSharedBudgetWithoutIntegerOverflow() {
        try (var singleton = mockStatic(Minecraft.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            var screen = new NetworkToolScreen(new NetworkToolSyncPayload(BlockPos.ZERO, "gui.telecom.tool.edge.copper",
                    100, 800, 800, 300, 200, CapacityMode.SHARED));
            screen.width = 320;
            screen.height = 240;
            var graphics = mock(GuiGraphics.class);
            screen.render(graphics, 0, 0, 0);
            var rates = rates(graphics);
            assertEquals(1, rates.size());
            assertEquals(500L, rates.getFirst()[1]);
            assertEquals(800, rates.getFirst()[2]);
            verify(graphics).fill(15, 129, 196, 134, 0xFF00FFFF);
        }
        assertEquals(0, NetworkToolScreen.utilization(100, 0));
        assertEquals(0.625, NetworkToolScreen.utilization(500, 800));
        assertEquals(2, NetworkToolScreen.utilization(2L * Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void serverRefreshReplacesBothUsageAndCapacities() {
        try (var singleton = mockStatic(Minecraft.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            var screen = new ServerScreen(new ServerGuiSyncPayload(new java.util.UUID(1, 2), "minecraft:overworld", true, true,
                    BlockPos.ZERO, 1, 2, 3, 10, 20, 100000, 100000));
            screen.updatePayload(new ServerGuiSyncPayload(new java.util.UUID(1, 2), "minecraft:overworld", false, true,
                    BlockPos.ZERO, 1, 2, 3, 500000, 200000, 1000000, 800000));
            screen.width = 320;
            screen.height = 240;
            var graphics = mock(GuiGraphics.class);
            screen.render(graphics, 0, 0, 0);
            var rates = rates(graphics);
            assertEquals(2, rates.size());
            assertArrayEquals(new Object[]{500000, 1000000}, new Object[]{rates.get(0)[1], rates.get(0)[2]});
            assertArrayEquals(new Object[]{200000, 800000}, new Object[]{rates.get(1)[1], rates.get(1)[2]});
            verify(graphics).fill(15, 154, 160, 159, 0xFF00FFFF);
            verify(graphics).fill(15, 189, 87, 194, 0xFFFF8800);
        }
    }

    @Test
    void capacityLabelsExistInBothLanguages() throws Exception {
        var english = language("en_us");
        var french = language("fr_fr");
        for (String key : english.keySet()) {
            if (!key.startsWith("gui.telecom.capacity.") && !key.startsWith("gui.telecom.tool.") && !key.startsWith("gui.telecom.server.")) continue;
            assertTrue(french.has(key), key);
            assertFalse(french.get(key).getAsString().isBlank(), key);
            assertEquals(english.get(key).getAsString().chars().filter(character -> character == '%').count(),
                    french.get(key).getAsString().chars().filter(character -> character == '%').count(), key);
        }
        assertTrue(french.get("gui.telecom.capacity.shared").getAsString().contains("partagé"));
        assertTrue(english.get("gui.telecom.capacity.shared").getAsString().contains("Shared"));
    }

    private static com.google.gson.JsonObject language(String name) throws Exception {
        try (var stream = CapacityScreensTest.class.getResourceAsStream("/assets/telecom/lang/" + name + ".json")) {
            assertNotNull(stream);
            try (var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static List<Object[]> rates(GuiGraphics graphics) {
        var components = ArgumentCaptor.forClass(Component.class);
        verify(graphics, atLeastOnce()).drawString(nullable(Font.class), components.capture(), anyInt(), anyInt(), anyInt());
        return components.getAllValues().stream().map(Component::getContents)
                .filter(contents -> contents instanceof TranslatableContents translated && translated.getKey().equals("gui.telecom.capacity.rate"))
                .map(contents -> ((TranslatableContents) contents).getArgs()).toList();
    }
}
