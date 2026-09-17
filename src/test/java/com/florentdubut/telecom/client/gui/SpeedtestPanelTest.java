package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.client.ClientSpeedtestState;
import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2fStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeedtestPanelTest {
    @Test
    void unitConversionKeepsExactIntegerServerMeansAndNeverRoundsUpLiveRate() {
        assertEquals("0 Mbps", SpeedtestPanel.rate(0));
        assertEquals("999 Mbps", SpeedtestPanel.rate(999.99));
        assertEquals("1.001 Gbps", SpeedtestPanel.rate(1001));
        assertEquals("12.345 Gbps", SpeedtestPanel.rate(12345));
        assertEquals("2147483.647 Gbps", SpeedtestPanel.rate(Integer.MAX_VALUE));
    }

    @Test
    void straightCurveContainsEveryReceivedPointAndNeverOvershootsLocalScale() {
        var graphics = mock(GuiGraphics.class);
        var points = IntStream.range(0, 120).mapToObj(i -> new ClientSpeedtestState.Point(i, i % 2 == 0 ? 0 : 1000)).toList();
        SpeedtestPanel.curve(graphics, points, 10, 20, 120, 24, 119, 1000, SpeedtestPanel.DOWN);
        for (int i = 0; i < 120; i++) {
            int x = 10 + (int) ((double) i / 119 * 119);
            int y = i % 2 == 0 ? 44 : 20;
            verify(graphics, atLeastOnce()).fill(x, y, x + 1, y + 1, SpeedtestPanel.DOWN);
        }
        for (var invocation : mockingDetails(graphics).getInvocations()) {
            Object[] args = invocation.getArguments();
            assertTrue((int) args[0] >= 10 && (int) args[2] <= 130);
            assertTrue((int) args[1] >= 20 && (int) args[3] <= 45);
        }
    }

    @Test
    void panelRendersBoth120PointPhasesAndStopsActivityOnSilenceAndFinish() {
        var graphics = graphics();
        var font = font();
        var points = IntStream.range(0, 120).mapToObj(i -> new ClientSpeedtestState.Point(i * 2, 1000 - i)).toList();
        var live = snapshot("UPLOAD", points, points);
        SpeedtestPanel.render(graphics, font, 10, 10, 200, 110, live, 0, 0, 0, 300, 500_000_000L);
        assertTrue(mockingDetails(graphics).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("fill")
                && i.getArgument(4).equals(SpeedtestPanel.DOWN)));
        assertTrue(mockingDetails(graphics).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("fill")
                && i.getArgument(4).equals(SpeedtestPanel.UP)));
        assertEquals(1, activityCount(graphics));
        clearInvocations(graphics);
        SpeedtestPanel.render(graphics, font, 10, 10, 200, 110, live, 0, 0, 0, 300, 5_000_000_000L);
        assertEquals(0, activityCount(graphics));
        clearInvocations(graphics);
        var finished = snapshot("FINISHED", points, points);
        SpeedtestPanel.render(graphics, font, 10, 10, 200, 110, finished, 0, 0, 0, 300, 9_000_000_000L);
        verify(graphics).drawString(font, "1.001 Gbps", 0, 0, SpeedtestPanel.DOWN);
        assertEquals(0, finished.instantaneous(9_000_000_000L));
        assertEquals(0, activityCount(graphics));
        assertEquals(120, finished.download().size());
        assertEquals(120, finished.upload().size());
        assertEquals(new Matrix3x2fStack(8), graphics.pose(), "Large type must restore the GUI transform");
    }

    @Test
    void pingRendersLatencyInsteadOfFakeBandwidth() {
        var graphics = graphics();
        var font = font();
        SpeedtestPanel.render(graphics, font, 10, 10, 200, 110, snapshot("PING", List.of(), List.of()),
                0, 0, 0, 300, 1_000_000_000L);
        verify(graphics).drawString(font, "17 ms", 0, 0, SpeedtestPanel.DOWN);
        assertEquals(1, activityCount(graphics));
    }

    @Test
    void sharedWindowUsesLongestSpanAndAlignsEachPhasesOwnLastPointRight() {
        var down = List.of(new ClientSpeedtestState.Point(11600, 0), new ClientSpeedtestState.Point(11900, 1000));
        var up = List.of(new ClientSpeedtestState.Point(7000, 1000), new ClientSpeedtestState.Point(7100, 0));
        int window = SpeedtestPanel.windowTicks(down, up);
        assertEquals(300, window);
        var graphics = graphics();
        SpeedtestPanel.curve(graphics, down, 10, 20, 181, 20, window, 1000, SpeedtestPanel.DOWN);
        SpeedtestPanel.curve(graphics, up, 10, 20, 181, 20, window, 1000, SpeedtestPanel.UP);
        verify(graphics).fill(10, 40, 11, 41, SpeedtestPanel.DOWN);
        verify(graphics).fill(190, 20, 191, 21, SpeedtestPanel.DOWN);
        verify(graphics).fill(130, 20, 131, 21, SpeedtestPanel.UP);
        verify(graphics).fill(190, 40, 191, 41, SpeedtestPanel.UP);
        assertEquals(1, SpeedtestPanel.windowTicks(List.of(), List.of()));
        var singleZero = List.of(new ClientSpeedtestState.Point(0, 0));
        assertEquals(1, SpeedtestPanel.windowTicks(singleZero, List.of()));
        clearInvocations(graphics);
        SpeedtestPanel.curve(graphics, singleZero, 10, 20, 181, 20, 1, 0, SpeedtestPanel.DOWN);
        verify(graphics).fill(190, 40, 191, 41, SpeedtestPanel.DOWN);
        assertEquals(1, mockingDetails(graphics).getInvocations().size());
    }

    @Test
    void sixHundredSecondPhasesUseReadableWidthAtCheckpointsAndFinishInBothMinimumSizePanels() {
        for (int checkpoint : new int[]{200, 6000, 12000}) {
            var down = IntStream.range(0, 120).mapToObj(i -> new ClientSpeedtestState.Point(11879 + i, i * 8)).toList();
            var up = IntStream.range(0, 120).mapToObj(i -> new ClientSpeedtestState.Point(checkpoint - 120 + i, i * 4)).toList();
            var payload = new SpeedtestUpdatePayload("ip", checkpoint == 12000 ? "FINISHED" : "UPLOAD", 17, 476,
                    checkpoint - 1, 12000, "dimension", "device", new UUID(1, 1), 1001, 257);
            var snapshot = new ClientSpeedtestState.Snapshot(payload, false, down, up, 952, 0, 3_000_000_000L, 0, "", 12060 + checkpoint - 1);
            for (int[] panel : new int[][]{{60, 116, 200, 110}, {14, 110, 292, 116}}) {
                int x = panel[0], y = panel[1], width = panel[2], height = panel[3];
                var graphics = graphics();
                var font = font();
                SpeedtestPanel.render(graphics, font, x, y, width, height, snapshot, 0, 0, 0, 12000, 500_000_000L);
                int left = x + 8, right = x + width - 9, top = y + 74, bottom = y + height - 19;
                assertTrue(right - left >= 183);
                verify(graphics, atLeastOnce()).fill(left, bottom, left + 1, bottom + 1, SpeedtestPanel.DOWN);
                verify(graphics, atLeastOnce()).fill(left, bottom, left + 1, bottom + 1, SpeedtestPanel.UP);
                verify(graphics, atLeastOnce()).fill(right, top, right + 1, top + 1, SpeedtestPanel.DOWN);
                int upY = bottom - (bottom - top) / 2;
                verify(graphics, atLeastOnce()).fill(right, upY, right + 1, upY + 1, SpeedtestPanel.UP);
                verify(graphics).drawString(font, SpeedtestPanel.text("window", "5.95").getString(), x + 7, y + 64, 0xFFAAAABB);
                verify(graphics).drawString(font, SpeedtestPanel.text("local_max", "952 Mbps").getString(), x + 7, y + 54, 0xFFAAAABB);
                for (var call : mockingDetails(graphics).getInvocations()) {
                    if (!call.getMethod().getName().equals("fill")) continue;
                    Object[] args = call.getArguments();
                    assertTrue((int) args[0] >= x && (int) args[2] <= x + width);
                    assertTrue((int) args[1] >= y && (int) args[3] <= y + height);
                }
                if (checkpoint == 12000) {
                    assertEquals(100, snapshot.percent(500_000_000L));
                    verify(graphics).drawString(font, "1.001 Gbps", 0, 0, SpeedtestPanel.DOWN);
                }
            }
        }
    }

    @Test
    void failedWithoutConfirmationRendersUnknownProgressAndNeverNegativeBar() {
        var graphics = graphics();
        var font = font();
        var failed = snapshot("FAILED", List.of(), List.of());
        SpeedtestPanel.render(graphics, font, 10, 10, 200, 110, failed, 0, 0, 0, 300, 5_000_000_000L);
        verify(graphics).drawString(font, SpeedtestPanel.text("progress_unknown", "33").getString(), 17, 105, 0xFFAAAABB);
        assertEquals(0, activityCount(graphics));
        for (var call : mockingDetails(graphics).getInvocations()) {
            if (!call.getMethod().getName().equals("fill")) continue;
            assertTrue((int) call.getArgument(0) <= (int) call.getArgument(2));
        }
        verify(graphics).fill(17, 116, 17, 118, SpeedtestPanel.DOWN);
    }

    @Test
    void failedWithConfirmationDisplaysTwentyEightSecondsInsteadOfFailurePacketsPhaseTicks() {
        var graphics = graphics();
        var font = font();
        var payload = new SpeedtestUpdatePayload("ip", "FAILED", 17, 0, 200, 300,
                "dimension", "device", new UUID(1, 1), 1001, 257);
        var failed = new ClientSpeedtestState.Snapshot(payload, false, List.of(), List.of(), 1000,
                0, 3_000_000_000L, 0, "route_lost", 560);
        SpeedtestPanel.render(graphics, font, 10, 10, 200, 110, failed, 0, 0, 0, 300, 90_000_000_000L);
        verify(graphics).drawString(font, SpeedtestPanel.text("progress", 84, "28.0", "33").getString(), 17, 105, 0xFFAAAABB);
        assertEquals(0, activityCount(graphics));
        assertEquals(560, failed.elapsedTicks(90_000_000_000L));
        verify(graphics).fill(17, 116, 17 + 186 * 84 / 100, 118, SpeedtestPanel.DOWN);
    }

    @Test
    void legacySavedValuesAreNotRelabeledAsMeasuredAverages() {
        var graphics = graphics();
        var font = font();
        var saved = new ClientSpeedtestState.Snapshot(null, false, List.of(), List.of(), 0,
                0, 3_000_000_000L, 0, "", -1);
        SpeedtestPanel.render(graphics, font, 10, 10, 200, 110, saved, 1001, 257, 17, 300, 0);
        verify(graphics).drawString(font, SpeedtestPanel.text("saved_down", "1.001 Gbps").getString(), 17, 44, SpeedtestPanel.DOWN);
        verify(graphics).drawString(font, SpeedtestPanel.text("saved_up", "257 Mbps").getString(), 17, 54, SpeedtestPanel.UP);
        verify(graphics, never()).drawString(font, SpeedtestPanel.text("average_down", "1.001 Gbps").getString(), 17, 44, SpeedtestPanel.DOWN);
        assertTrue(saved.download().isEmpty());
        assertTrue(saved.upload().isEmpty());
        assertEquals(0, activityCount(graphics));
    }

    @Test
    void allSpeedtestTranslationsExistInBothLanguagesWithMatchingPlaceholders() throws Exception {
        var english = language("en_us");
        var french = language("fr_fr");
        for (String key : english.keySet()) {
            if (!key.startsWith("gui.telecom.speedtest.")) continue;
            assertTrue(french.has(key), key);
            assertFalse(french.get(key).getAsString().isBlank(), key);
            assertEquals(english.get(key).getAsString().chars().filter(c -> c == '%').count(),
                    french.get(key).getAsString().chars().filter(c -> c == '%').count(), key);
        }
        assertTrue(french.get("gui.telecom.speedtest.duration").getAsString().contains("/ phase"));
        assertTrue(english.get("gui.telecom.speedtest.local_max").getAsString().contains("observed"));
        assertEquals("Dernières %s s / phase", french.get("gui.telecom.speedtest.window").getAsString());
        assertTrue(english.get("gui.telecom.speedtest.progress_unknown").getAsString().contains("--"));
    }

    private static com.google.gson.JsonObject language(String language) throws Exception {
        try (var stream = SpeedtestPanelTest.class.getResourceAsStream("/assets/telecom/lang/" + language + ".json")) {
            assertNotNull(stream);
            try (var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static long activityCount(GuiGraphics graphics) {
        return mockingDetails(graphics).getInvocations().stream().filter(i -> i.getMethod().getName().equals("fill")
                && i.getArgument(1).equals(103) && i.getArgument(3).equals(104)).count();
    }

    private static ClientSpeedtestState.Snapshot snapshot(String phase, List<ClientSpeedtestState.Point> down,
                                                         List<ClientSpeedtestState.Point> up) {
        var payload = new SpeedtestUpdatePayload("ip", phase, 17, 900, 40, 300,
                "dimension", "device", new UUID(1, 1), 1001, 257);
        return new ClientSpeedtestState.Snapshot(payload, false, down, up, 1000, 0, 3_000_000_000L, 0, "", -1);
    }

    private static Font font() {
        var font = mock(Font.class);
        when(font.plainSubstrByWidth(anyString(), anyInt())).thenAnswer(i -> i.getArgument(0));
        when(font.width(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).length() * 6);
        return font;
    }

    private static GuiGraphics graphics() {
        var graphics = mock(GuiGraphics.class);
        when(graphics.pose()).thenReturn(new Matrix3x2fStack(8));
        return graphics;
    }
}
