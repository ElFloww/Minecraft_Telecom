package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.CoverageTilePayload;
import com.florentdubut.telecom.network.packet.RequestCoverageTilePayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CoverageMapStateTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final CoverageMapState.Tile TILE = new CoverageMapState.Tile(0, 0, -3, 1);

    @Test
    void adaptiveGridCoversWholeViewportWithinBudget() {
        for (int width : new int[]{180, 320, 854, 1920, 3840}) {
            for (int height : new int[]{80, 240, 1080, 2160}) {
                for (double requestedZoom : new double[]{0.05, 0.2, 1, 5, 32}) {
                    for (int precision : new int[]{1, 8, 16}) {
                        double zoom = Math.max(requestedZoom, CoverageMapState.minimumZoom(width, height));
                        double offsetX = width / 2.0 + 17, offsetZ = height / 2.0 - 123;
                        var tiles = CoverageMapState.visibleTiles(width, height, offsetX, offsetZ, zoom, precision);
                        assertFalse(tiles.isEmpty());
                        assertTrue(tiles.size() <= 16);
                        for (var tile : tiles) {
                            assertTrue(tile.level() >= -3 && tile.level() <= 6);
                            assertTrue(tile.step() >= precision);
                            assertEquals(0, tile.span() % tile.step());
                            assertTrue(tile.span() / tile.step() <= 16);
                        }
                        int span = tiles.getFirst().span();
                        assertTrue(tiles.stream().mapToInt(CoverageMapState.Tile::x).min().orElseThrow() * span * zoom + offsetX <= 0);
                        assertTrue((tiles.stream().mapToInt(CoverageMapState.Tile::x).max().orElseThrow() + 1) * span * zoom + offsetX >= width);
                        assertTrue(tiles.stream().mapToInt(CoverageMapState.Tile::z).min().orElseThrow() * span * zoom + offsetZ <= 0);
                        assertTrue((tiles.stream().mapToInt(CoverageMapState.Tile::z).max().orElseThrow() + 1) * span * zoom + offsetZ >= height);
                    }
                }
            }
        }
    }

    @Test
    void closeZoomAllowsRequestedOneBlockSampling() {
        var tiles = CoverageMapState.visibleTiles(854, 480, 427, 240, 32, 1);
        assertFalse(tiles.isEmpty());
        assertTrue(tiles.stream().allMatch(tile -> tile.level() == -3 && tile.step() == 1));
    }

    @Test
    void negativeCoordinatesAndWorldBorderDoNotProduceInvalidRequests() {
        var tiles = CoverageMapState.visibleTiles(16, 16, 16, 16, 1, 1);
        assertEquals(List.of(new CoverageMapState.Tile(-1, -1, -3, 1)), tiles);
        for (double offset : new double[]{-29_999_999, 29_999_999}) {
            for (var tile : CoverageMapState.visibleTiles(320, 240, offset, offset, 1, 16)) {
                assertDoesNotThrow(() -> new RequestCoverageTilePayload(UUID.randomUUID(), DIMENSION,
                        tile.x(), tile.z(), tile.step(), "surface", "all", "all", "all", tile.level()).request());
            }
        }
    }

    @Test
    void rateLimitSurvivesFilterChangesAndDroppedRepliesRetry() {
        var state = state();
        var first = request(state, 0);
        assertNotNull(first);
        assertNull(request(state, 3));
        state.reset(DIMENSION, List.of(TILE));
        assertNotEquals(first.viewId(), state.viewId());
        assertNull(request(state, 3));
        assertNotNull(request(state, 4));
        assertNull(request(state, 23));
        assertNotNull(request(state, 24));
    }

    @Test
    void pendingAndReadyHaveDistinctPollingDeadlines() {
        var state = state();
        var request = request(state, 0);
        assertTrue(state.receive(response(request, "r1", "pending", List.of()), 1));
        assertNull(request(state, 20));
        request = request(state, 21);
        assertNotNull(request);
        assertTrue(state.receive(response(request, "r1", "ready", List.of()), 22));
        assertNull(request(state, 121));
        assertNotNull(request(state, 122));
    }

    @Test
    void busyBacksOffAndInvalidStopsUntilViewChanges() {
        var state = state();
        var request = request(state, 0);
        assertTrue(state.receive(response(request, "", "busy", List.of()), 1));
        assertNull(request(state, 40));
        request = request(state, 41);
        assertTrue(state.receive(response(request, "", "invalid", List.of()), 42));
        assertNull(request(state, 10000));
        state.reset(DIMENSION, List.of(TILE));
        assertNotNull(request(state, 10001));
    }

    @Test
    void rejectsOldViewsDimensionsUnrequestedTilesAndInvalidCells() {
        var state = state();
        var first = request(state, 0);
        state.reset(DIMENSION, List.of(TILE));
        assertFalse(state.receive(response(first, "r1", "ready", List.of()), 1));
        var current = request(state, 4);
        var wrongDimension = new CoverageTilePayload(current.viewId(), "minecraft:the_nether", "r1",
                0, 0, 1, -3, "ready", 1, List.of());
        assertFalse(state.receive(wrongDimension, 5));
        assertFalse(state.receive(new CoverageTilePayload(current.viewId(), DIMENSION, "r1",
                1, 0, 1, -3, "ready", 1, List.of()), 5));
        assertFalse(state.receive(response(current, "r1", "ready", List.of(cell(16, "signal", -70))), 5));
        state.reset("", List.of());
        assertFalse(state.receive(response(current, "r1", "ready", List.of()), 6));
        assertNull(request(state, 100));
    }

    @Test
    void modelChangeFlushesOtherTilesAndRejectsInFlightOldRevision() {
        var state = state();
        var other = new CoverageMapState.Tile(1, 0, -3, 1);
        state.reset(DIMENSION, List.of(TILE, other));
        var first = request(state, 0);
        assertTrue(state.receive(response(first, "r1", "ready", List.of()), 1));
        var second = request(state, 4);
        assertTrue(state.receive(response(second, "r2", "ready", List.of()), 5));
        assertNotEquals(first.viewId(), state.viewId());
        assertNull(state.data(TILE, 6));
        assertNotNull(state.data(other, 6));
        assertFalse(state.receive(response(first, "r1", "ready", List.of()), 6));
    }

    @Test
    void lostRefreshDoesNotExtendTheLifetimeOfOldRadioSamples() {
        var state = state();
        var request = request(state, 0);
        assertTrue(state.receive(response(request, "r1", "ready", List.of(cell(0, "signal", -70))), 1));
        assertNotNull(state.data(TILE, 100));
        assertNotNull(request(state, 101));
        assertNull(state.data(TILE, 101));
        assertNotNull(request(state, 121));
        assertNull(state.data(TILE, 121));
        assertTrue(state.receive(response(request, "r1", "ready", List.of(cell(0, "signal", -90))), 122));
        assertEquals(-90, state.data(TILE, 122).cells().getFirst().powerDbm());
    }

    @Test
    void panRetainsUnexpiredVisibleTilesWithoutAcceptingOldViewReplies() {
        var state = state();
        var other = new CoverageMapState.Tile(1, 0, -3, 1);
        state.reset(DIMENSION, List.of(TILE, other));
        var first = request(state, 0);
        var ready = response(first, "r1", "ready", List.of(cell(0, "signal", -70)));
        assertTrue(state.receive(ready, 1));
        request(state, 4);
        state.move(List.of(TILE));
        assertEquals(List.of(TILE), state.tiles());
        assertEquals(ready, state.data(TILE, 5));
        assertNotEquals(first.viewId(), state.viewId());
        assertFalse(state.receive(ready, 5));
        assertNull(request(state, 100));
        assertNotNull(request(state, 101));
        assertNull(state.data(TILE, 101));
    }

    @Test
    void navigationCannotPreserveSamplesWhenReceiverHeightChangedBeforeTheNextTick() throws Exception {
        var minecraft = org.mockito.Mockito.mock(net.minecraft.client.Minecraft.class);
        minecraft.player = org.mockito.Mockito.mock(net.minecraft.client.player.LocalPlayer.class);
        org.mockito.Mockito.when(minecraft.player.blockPosition()).thenReturn(new net.minecraft.core.BlockPos(0, 63, 0));
        try (var singleton = org.mockito.Mockito.mockStatic(net.minecraft.client.Minecraft.class)) {
            singleton.when(net.minecraft.client.Minecraft::getInstance).thenReturn(minecraft);
            var screen = new NetworkMapScreen();
            var client = net.minecraft.client.gui.screens.Screen.class.getDeclaredField("minecraft");
            client.setAccessible(true);
            client.set(screen, minecraft);
            screen.width = 16;
            for (var setting : java.util.Map.<String, Object>of("coverageEnabled", true, "heightMode", 1,
                    "dimension", DIMENSION, "mapBottom", 16).entrySet()) {
                var field = NetworkMapScreen.class.getDeclaredField(setting.getKey());
                field.setAccessible(true);
                field.set(screen, setting.getValue());
            }
            var changed = NetworkMapScreen.class.getDeclaredMethod("viewChanged", boolean.class);
            changed.setAccessible(true);
            changed.invoke(screen, false);
            var height = NetworkMapScreen.class.getDeclaredField("lastHeight");
            height.setAccessible(true);
            assertEquals("64", height.get(screen));
            var cache = NetworkMapScreen.class.getDeclaredField("coverage");
            cache.setAccessible(true);
            var state = (CoverageMapState) cache.get(screen);
            var request = state.nextRequest(0, "64", "all", "all", "all");
            assertTrue(state.receive(response(request, "r1", "limited", List.of()), 1));
            var tile = state.tiles().getFirst();
            org.mockito.Mockito.when(minecraft.player.blockPosition()).thenReturn(new net.minecraft.core.BlockPos(0, 64, 0));
            changed.invoke(screen, true);
            assertEquals("65", height.get(screen));
            assertNull(state.data(tile, 2));
            assertNotNull(state.nextRequest(4, "65", "all", "all", "all"));
        }
    }

    @Test
    void permanentCalculationLimitDoesNotPollUntilFiltersAreChanged() {
        var state = state();
        var request = request(state, 0);
        assertTrue(state.receive(response(request, "", "limited", List.of()), 1));
        assertNull(request(state, 10000));
        state.move(List.of(TILE));
        assertNull(request(state, 10001));
        state.reset(DIMENSION, List.of(TILE));
        assertNotNull(request(state, 10002));
    }

    @Test
    void panningNeverRetainsOffScreenCache() {
        var state = state();
        for (int x = 0; x < 1000; x++) {
            var tile = new CoverageMapState.Tile(x, 0, -3, 1);
            state.reset(DIMENSION, List.of(tile));
            var request = request(state, x * 4L);
            assertTrue(state.receive(response(request, "r1", "ready", List.of()), x * 4L));
            assertEquals(List.of(tile), state.tiles());
        }
    }

    @Test
    void signalThresholdsMatchWebAndUnknownNeverBecomesAbsent() {
        assertEquals("strong", CoverageMapState.signalState(cell(0, "signal", -79)));
        assertEquals("medium", CoverageMapState.signalState(cell(0, "signal", -80)));
        assertEquals("weak", CoverageMapState.signalState(cell(0, "signal", -100)));
        assertEquals("below", CoverageMapState.signalState(cell(0, "signal", -120)));
        assertEquals("unknown", CoverageMapState.signalState(cell(0, "unknown", 0)));
        assertEquals("unknown", CoverageMapState.signalState(cell(0, "signal", Float.NaN)));
        assertEquals("none", CoverageMapState.signalState(cell(0, "none", Float.NaN)));
    }

    private static CoverageMapState state() {
        var state = new CoverageMapState();
        state.reset(DIMENSION, List.of(TILE));
        return state;
    }

    private static RequestCoverageTilePayload request(CoverageMapState state, long tick) {
        return state.nextRequest(tick, "surface", "all", "all", "all");
    }

    private static CoverageTilePayload response(RequestCoverageTilePayload request, String revision,
                                                String status, List<CoverageTilePayload.Cell> cells) {
        return new CoverageTilePayload(request.viewId(), request.dimension(), revision, request.tileX(),
                request.tileZ(), request.step(), request.level(), status, status.equals("ready") ? 1 : 0.5f, cells);
    }

    private static CoverageTilePayload.Cell cell(int x, String state, float power) {
        return new CoverageTilePayload.Cell(x, 64, 0, state, power, "4G", "G4_700", "0", "available");
    }
}
