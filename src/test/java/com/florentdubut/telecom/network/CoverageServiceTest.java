package com.florentdubut.telecom.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.florentdubut.telecom.network.TelecomFrequency.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class CoverageServiceTest {
    private ServerLevel level;
    private MinecraftServer server;
    private ServerChunkCache chunks;
    private LevelChunk chunk;
    private TelecomNetworkGraph graph;
    private MockedStatic<TelecomNetworkGraph> graphs;

    @BeforeEach
    void setUp() {
        CoverageService.clear();
        level = mock(ServerLevel.class);
        server = mock(MinecraftServer.class);
        chunks = mock(ServerChunkCache.class);
        chunk = mock(LevelChunk.class);
        when(server.getAllLevels()).thenReturn(List.of(level));
        when(level.getChunkSource()).thenReturn(chunks);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(319);
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        when(chunk.getHeight(eq(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES), anyInt(), anyInt())).thenReturn(64);
        graph = new TelecomNetworkGraph();
        graphs = mockStatic(TelecomNetworkGraph.class);
        graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
    }

    @AfterEach
    void tearDown() {
        if (graphs != null) graphs.close();
        CoverageService.clear();
    }

    @Test
    void validatesTileBoundsWithoutIntegerOverflowAndAllSupportedSteps() {
        for (int coordinate : new int[]{-234374, 0, 234374}) {
            for (int step : new int[]{8, 16, 32, 64, 128}) {
                assertDoesNotThrow(() -> new CoverageService.Request(coordinate, -coordinate, step,
                        "surface", "all", "all", "all"));
            }
        }
        for (int coordinate : new int[]{Integer.MIN_VALUE, -234375, 234375, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> request(coordinate));
            assertThrows(IllegalArgumentException.class, () -> new CoverageService.Request(0, coordinate, 64,
                    "surface", "all", "all", "all"));
        }
        for (int step : new int[]{-16, 0, 1, 2, 4, 17, 256, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new CoverageService.Request(0, 0, step,
                    "surface", "all", "all", "all"));
        }
    }

    @Test
    void coverageLevelsKeepAtMost256SamplesWithIntegralRatios() {
        for (int level = -3; level <= 6; level++) {
            int span = level >= 0 ? 128 << level : 128 >> -level;
            for (int step : new int[]{1, 8, 16, 32, 64, 128, 256, 512, 1024, 2048}) {
                final int lod = level;
                if (step <= span && span % step == 0 && span / step <= 16) {
                    var request = new CoverageService.Request(0, 0, step, "64", "all", "all", "all", lod);
                    assertEquals(span, request.tileSize());
                } else {
                    assertThrows(IllegalArgumentException.class, () -> new CoverageService.Request(0, 0, step, "64", "all", "all", "all", lod));
                }
            }
        }
        for (int level : new int[]{Integer.MIN_VALUE, -4, 7, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new CoverageService.Request(0, 0, 16, "64", "all", "all", "all", level));
        }
        for (int coordinate : new int[]{Integer.MIN_VALUE, -1875000, 1875000, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new CoverageService.Request(coordinate, 0, 1, "64", "all", "all", "all", -3));
        }
        assertEquals(16, new CoverageService.Request(1874999, -1874999, 1, "64", "all", "all", "all", -3).tileSize());
        assertThrows(IllegalArgumentException.class, () -> new CoverageService.Request(234374, 0, 64, "64", "all", "all", "all", 2));
    }

    @Test
    void coverageLodUsesFullExtentAndIndependentCacheKeys() {
        var expanded = new CoverageService.Request(-1, 1, 64, "64", "all", "all", "all", 2);
        JsonObject result = ready(expanded);
        assertEquals(512, result.get("tileSize").getAsInt());
        assertEquals(-512, result.get("originX").getAsInt());
        assertEquals(512, result.get("originZ").getAsInt());
        assertEquals(64, result.getAsJsonArray("cells").size());
        JsonObject first = result.getAsJsonArray("cells").get(0).getAsJsonObject();
        JsonObject last = result.getAsJsonArray("cells").get(63).getAsJsonObject();
        assertEquals(-480, first.get("x").getAsInt());
        assertEquals(544, first.get("z").getAsInt());
        assertEquals(-32, last.get("x").getAsInt());
        assertEquals(992, last.get("z").getAsInt());
        var original = new CoverageService.Request(-1, 1, 64, "64", "all", "all", "all");
        assertNotEquals(result.get("revision"), ready(original).get("revision"));
    }

    @Test
    void validatesHeightAntennaTechnologyAndBand() {
        for (String height : List.of("", "Surface", "64.5", "2147483648")) {
            assertThrows(IllegalArgumentException.class, () -> filtered(height, "all", "all", "all"));
        }
        for (String antenna : List.of("", "1.5", "abc", "9223372036854775808")) {
            assertThrows(IllegalArgumentException.class, () -> filtered("64", antenna, "all", "all"));
        }
        for (String technology : List.of("", "4g", "6G")) {
            assertThrows(IllegalArgumentException.class, () -> filtered("64", "all", technology, "all"));
        }
        assertThrows(IllegalArgumentException.class, () -> filtered("64", "all", "all", "B28"));
        assertThrows(IllegalArgumentException.class, () -> filtered("64", "all", "4G", "G5_700"));
        for (TelecomFrequency frequency : TelecomFrequency.values()) {
            assertDoesNotThrow(() -> filtered("64", "all", frequency.getTechnology(), frequency.name()));
            assertDoesNotThrow(() -> filtered("64", "all", "all", frequency.name()));
        }
        for (String antenna : List.of(Long.toString(Long.MIN_VALUE), Long.toString(Long.MAX_VALUE))) {
            assertDoesNotThrow(() -> filtered("-64", antenna, "all", "all"));
        }
    }

    @Test
    void validatesExplicitHeightAgainstInclusiveDimensionBounds() {
        for (String height : List.of("-65", "320")) {
            assertThrows(IllegalArgumentException.class, () -> snapshot(filtered(height, "all", "all", "all")));
        }
        for (String height : List.of("-64", "319")) {
            JsonObject tile = ready(filtered(height, "all", "all", "all"));
            tile.getAsJsonArray("cells").forEach(cell -> {
                assertEquals(Integer.parseInt(height), cell.getAsJsonObject().get("y").getAsInt());
                assertEquals("none", cell.getAsJsonObject().get("state").getAsString());
            });
        }
        verifyNoInteractions(chunk);
    }

    @Test
    void identicalValueRequestsReusePendingAndReadyRevisionWithoutTerrainReadsOnRequest() {
        var request = request(0);
        JsonObject pending = snapshot(request);
        assertEquals("pending", pending.get("status").getAsString());
        assertEquals(0, pending.get("progress").getAsDouble());
        assertEquals(0, pending.get("generatedAt").getAsLong());
        assertTrue(pending.getAsJsonArray("cells").isEmpty());
        assertEquals(pending, snapshot(request(0)));
        verifyNoInteractions(chunks);

        JsonObject completed = ready(request);
        assertEquals(revision(pending), revision(completed));
        assertEquals(1, completed.get("progress").getAsDouble());
        assertTrue(completed.get("generatedAt").getAsLong() > 0);
        assertEquals(4, completed.getAsJsonArray("cells").size());
        assertEquals(128, completed.get("tileSize").getAsInt());
        assertEquals(SignalPropagator.MAX_RANGE, completed.get("maxRange").getAsInt());
        JsonObject reused = snapshot(request(0));
        long remaining = reused.get("validForMs").getAsLong();
        assertTrue(remaining > 0 && remaining <= 30000);
        assertTrue(remaining <= completed.get("validForMs").getAsLong());
        completed.remove("validForMs");
        reused.remove("validForMs");
        assertEquals(completed, reused);
    }

    @Test
    void readyPhysicsSurvivesClientTtlAndExpiresOnlyAfterFiveIdleMinutes() throws Exception {
        antenna(new BlockPos(31, 64, 32), G4_700, G5_700);
        JsonObject completed = ready(request(0));
        Object job = storedJob(request(0));
        var completedAt = job.getClass().getDeclaredField("completedAt");
        completedAt.setAccessible(true);
        var requestedAt = job.getClass().getDeclaredField("requestedAt");
        requestedAt.setAccessible(true);
        clearInvocations(chunk, chunks);
        // Completion age is not a physical invalidation; only inactivity evicts a ready job.
        completedAt.setLong(job, System.nanoTime() - TimeUnit.MINUTES.toNanos(10));
        requestedAt.setLong(job, System.nanoTime() - TimeUnit.SECONDS.toNanos(299));
        JsonObject aged = snapshot(request(0));
        assertEquals(completed, aged);
        assertEquals(30000, aged.get("validForMs").getAsLong());
        CoverageService.tick(server);
        verifyNoInteractions(chunk, chunks);
        requestedAt.setLong(job, System.nanoTime() - TimeUnit.SECONDS.toNanos(301));
        JsonObject expired = snapshot(request(0));
        assertNotEquals(revision(completed), revision(expired));
        assertEquals("pending", expired.get("status").getAsString());
    }

    @Test
    void idlePendingJobSurvivesThirtySecondsButExpiresAfterFortyFive() throws Exception {
        JsonObject pending = snapshot(request(0));
        Object job = storedJob(request(0));
        var requestedAt = job.getClass().getDeclaredField("requestedAt");
        requestedAt.setAccessible(true);
        requestedAt.setLong(job, System.nanoTime() - TimeUnit.SECONDS.toNanos(35));
        assertEquals(revision(pending), revision(snapshot(request(0))));
        requestedAt.setLong(job, System.nanoTime() - TimeUnit.SECONDS.toNanos(46));
        assertNotEquals(revision(pending), revision(snapshot(request(0))));
    }

    @Test
    void tileCreationCompletionPollingAndEvictionDoNotChangeModelRevision() {
        String model = CoverageService.modelRevision(level);
        assertNotNull(model);
        assertFalse(model.isBlank(), "The model revision is an opaque, world-specific token");
        for (int i = 0; i < 129; i++) {
            assertEquals(model, snapshot(request(i)).get("modelRevision").getAsString());
            assertEquals(model, CoverageService.modelRevision(level), "Creating tile " + i);
            assertEquals(model, ready(request(i)).get("modelRevision").getAsString());
            assertEquals(model, CoverageService.modelRevision(level), "Completing tile " + i);
        }
        snapshot(request(128));
        assertEquals(model, CoverageService.modelRevision(level));
    }

    @Test
    void replacingTheWorldStateInvalidatesOldBrowserRevisions() {
        String previous = CoverageService.modelRevision(level);
        CoverageService.clear();
        assertNotEquals(previous, CoverageService.modelRevision(level));
        assertEquals(CoverageService.modelRevision(level), snapshot(request(0)).get("modelRevision").getAsString());
    }

    @Test
    void snapshotCapturesCurrentModelEvenWhenAnUnchangedPhysicalJobIsReused() {
        ready(request(0));
        JsonObject prepared = ready(request(10));
        String originalModel = prepared.get("modelRevision").getAsString();
        assertEquals(CoverageService.modelRevision(level), originalModel);

        CoverageService.invalidateChunk(level, 2, 2);
        JsonObject current = snapshot(request(10));
        assertEquals(revision(prepared), revision(current), "Unrelated terrain must not recompute this job");
        assertEquals(prepared.get("generatedAt"), current.get("generatedAt"));
        assertNotEquals(originalModel, current.get("modelRevision").getAsString());
        assertEquals(CoverageService.modelRevision(level), current.get("modelRevision").getAsString());
        assertEquals(originalModel, prepared.get("modelRevision").getAsString(), "Queued JSON retains its captured model");
        assertEquals(current.get("modelRevision"), snapshot(request(0)).get("modelRevision"));
    }

    @Test
    void onlyInvalidatingDependentEntriesChangesModelRevision() {
        antenna(new BlockPos(-32, 64, 32), G2_900);
        JsonObject tile = ready(request(0));
        String original = CoverageService.modelRevision(level);
        CoverageService.invalidateChunk(level, 500, 500);
        assertEquals(original, CoverageService.modelRevision(level));
        assertEquals(revision(tile), revision(snapshot(request(0))));

        CoverageService.invalidateChunk(level, -1, 2);
        String invalidated = CoverageService.modelRevision(level);
        assertNotEquals(original, invalidated);
        CoverageService.invalidateChunk(level, -1, 2);
        assertEquals(invalidated, CoverageService.modelRevision(level), "No entries remain to invalidate");
        JsonObject replacement = ready(request(0));
        assertNotEquals(revision(tile), revision(replacement));
        assertEquals(invalidated, CoverageService.modelRevision(level), "Recomputation is not a model change");
    }

    @Test
    void topologyInvalidationChangesModelRevisionOnceThroughEveryEntryPoint() {
        for (String entryPoint : List.of("modelRevision", "tick", "request")) {
            CoverageService.clear();
            JsonObject tile = ready(request(0));
            String before = CoverageService.modelRevision(level);
            BlockPos position = new BlockPos(0, 64, 0);
            graph.addNode(new NetworkNode(position, NetworkNode.NodeType.SERVER));
            if (entryPoint.equals("tick")) {
                CoverageService.tickUntil(server, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2));
            } else if (entryPoint.equals("request")) {
                JsonObject refreshed = snapshot(request(0));
                assertNotEquals(revision(tile), revision(refreshed));
                assertNotEquals(tile.get("modelRevision"), refreshed.get("modelRevision"));
                assertEquals(CoverageService.modelRevision(level), refreshed.get("modelRevision").getAsString());
            }
            String invalidated = CoverageService.modelRevision(level);
            assertNotEquals(before, invalidated, entryPoint);
            assertEquals(invalidated, CoverageService.modelRevision(level), "Repeated reads must be stable");
            CoverageService.tickUntil(server, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2));
            assertEquals(invalidated, CoverageService.modelRevision(level));
            assertNotEquals(revision(tile), revision(ready(request(0))));
            assertEquals(invalidated, CoverageService.modelRevision(level), "Refreshing must not invalidate twice");
        }
    }

    @Test
    void spectrumInvalidationChangesModelRevisionOnlyWhenEntriesExist() {
        CoverageService.invalidateAntennas(level);
        String empty = CoverageService.modelRevision(level);
        ready(request(0));
        assertEquals(empty, CoverageService.modelRevision(level));
        CoverageService.invalidateAntennas(level);
        String invalidated = CoverageService.modelRevision(level);
        assertNotEquals(empty, invalidated);
        CoverageService.invalidateAntennas(level);
        assertEquals(invalidated, CoverageService.modelRevision(level));
        ready(request(0));
        assertEquals(invalidated, CoverageService.modelRevision(level));
    }

    @Test
    void cacheKeyIncludesGeometryHeightAndAntennaButNotDisplayFilters() {
        List<CoverageService.Request> requests = List.of(request(0), request(1),
                new CoverageService.Request(0, 1, 64, "64", "all", "all", "all"),
                new CoverageService.Request(0, 0, 32, "64", "all", "all", "all"),
                filtered("surface", "all", "all", "all"), filtered("65", "all", "all", "all"),
                filtered("64", "1", "all", "all"),
                new CoverageService.Request(0, 0, 64, "64", "all", "all", "all", -1));
        List<String> revisions = requests.stream().map(this::snapshot).map(CoverageServiceTest::revision).toList();
        assertEquals(requests.size(), revisions.stream().distinct().count());
        for (int i = 0; i < requests.size(); i++) assertEquals(revisions.get(i), revision(snapshot(requests.get(i))));
        assertEquals(revisions.getFirst(), revision(snapshot(filtered("64", "all", "4G", "all"))));
        assertEquals(revisions.getFirst(), revision(snapshot(filtered("64", "all", "all", "G4_700"))));
    }

    @Test
    void expiredTickBudgetDoesNoWorkAndLaterTicksResumeTheSameJob() {
        antenna(new BlockPos(0, 64, 32), G2_900);
        var request = request(0);
        JsonObject pending = snapshot(request);
        CoverageService.tickUntil(server, System.nanoTime() - 1);
        assertEquals(pending, snapshot(request));
        verifyNoInteractions(chunks);
        assertEquals(revision(pending), revision(ready(request)));
        verify(server, atLeastOnce()).getAllLevels();
    }

    @Test
    void expiredSnapshotBudgetRejectsNewTopologyWithoutPoisoningRetries() {
        antenna(new BlockPos(0, 64, 32), G2_900);
        assertThrows(CoverageService.BusyException.class,
                () -> CoverageService.request(level, request(0), System.nanoTime() - 1));
        assertEquals("signal", firstCell(ready(request(0))).get("state").getAsString());
    }

    @Test
    void pendingQueueAcceptsExactlySixteenAndReusesExistingJobsWhenFull() {
        List<String> revisions = new ArrayList<>();
        for (int i = 0; i < 16; i++) revisions.add(revision(snapshot(request(i))));
        assertThrows(CoverageService.BusyException.class, () -> snapshot(request(16)));
        for (int i = 0; i < 16; i++) assertEquals(revisions.get(i), revision(snapshot(request(i))));
        assertEquals(revisions.getFirst(), revision(snapshot(filtered("64", "all", "5G", "all"))));
        ready(request(0));
        assertEquals("pending", snapshot(request(16)).get("status").getAsString());
    }

    @Test
    void cacheRetainsExactly128EntriesAndEvictsLeastRecentlyUsedCompletedJob() {
        List<String> revisions = new ArrayList<>();
        for (int i = 0; i < 128; i++) revisions.add(revision(ready(request(i))));
        assertEquals(revisions.getFirst(), revision(snapshot(request(0))));
        ready(request(128));
        assertEquals(revisions.getFirst(), revision(snapshot(request(0))));
        for (int i = 2; i < 128; i++) assertEquals(revisions.get(i), revision(snapshot(request(i))), "Tile " + i);
        assertNotEquals(revisions.get(1), revision(snapshot(request(1))));
    }

    @Test
    void cacheEvictionSkipsPendingJobsEvenWhenTheyAreOldest() {
        for (int i = 0; i < 128; i++) ready(request(i));
        String pending = revision(snapshot(request(128)));
        // Touch all completed entries, leaving the pending entry at the LRU end.
        for (int i = 1; i < 128; i++) snapshot(request(i));
        snapshot(request(129));
        assertEquals(pending, revision(snapshot(request(128))));
    }

    @Test
    void gridUsesCellCentersAndSurfaceHeightFromTheLoadedChunk() {
        var request = new CoverageService.Request(-1, -2, 16, "surface", "all", "all", "all");
        JsonObject tile = ready(request);
        assertEquals(-128, tile.get("originX").getAsInt());
        assertEquals(-256, tile.get("originZ").getAsInt());
        assertEquals(64, tile.getAsJsonArray("cells").size());
        for (int i = 0; i < 64; i++) {
            JsonObject cell = tile.getAsJsonArray("cells").get(i).getAsJsonObject();
            assertEquals(-128 + i % 8 * 16 + 8, cell.get("x").getAsInt());
            assertEquals(-256 + i / 8 * 16 + 8, cell.get("z").getAsInt());
            assertEquals(66, cell.get("y").getAsInt());
        }
        verify(chunk, times(64)).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8);
    }

    @Test
    void exactBlockHalfChunkAndChunkGridsHave256DistinctCenters() {
        for (int[] grid : List.of(new int[]{-3, 1}, new int[]{0, 8}, new int[]{1, 16})) {
            int lod = grid[0], step = grid[1], span = step * 16;
            var request = new CoverageService.Request(-1, 1, step, "64", "all", "all", "all", lod);
            JsonObject tile = ready(request);
            assertEquals(256, tile.getAsJsonArray("cells").size());
            for (int i = 0; i < 256; i++) {
                JsonObject cell = tile.getAsJsonArray("cells").get(i).getAsJsonObject();
                assertEquals(-span + i % 16 * step + step / 2, cell.get("x").getAsInt());
                assertEquals(span + i / 16 * step + step / 2, cell.get("z").getAsInt());
                assertEquals(64, cell.get("y").getAsInt());
                assertEquals(4, cell.getAsJsonObject("technologies").size());
            }
        }
    }

    @Test
    void fineGridSharesOneTerrainTraversalForAllBandsAndKeepsPayloadBounded() {
        BlockPos source = new BlockPos(0, 64, 0);
        NetworkNode node = antenna(source, G2_900);
        var request = new CoverageService.Request(0, 0, 1, "64", "all", "all", "all", -3);
        ready(request);
        int singleBandProbes = mockingDetails(chunk).getInvocations().size();
        node.setFrequenciesMask((1 << TelecomFrequency.values().length) - 1);
        CoverageService.invalidateAntennas(level);
        clearInvocations(chunk);
        JsonObject tile = ready(request);
        assertEquals(singleBandProbes, mockingDetails(chunk).getInvocations().size(), "Bands must share DDA terrain probes");
        assertEquals(256, tile.getAsJsonArray("cells").size());
        assertTrue(tile.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 1_000_000);
        for (var value : tile.getAsJsonArray("cells")) {
            JsonObject cell = value.getAsJsonObject();
            for (String technology : List.of("2G", "3G", "4G", "5G")) {
                JsonObject selected = cell.getAsJsonObject("technologies").getAsJsonObject(technology);
                assertEquals(6, selected.size());
                assertEquals("signal", selected.get("state").getAsString());
                assertEquals(technology, selected.get("technology").getAsString());
            }
        }
    }

    @Test
    void surfaceAboveDimensionIsUnknownRatherThanClamped() {
        when(chunk.getHeight(any(), anyInt(), anyInt())).thenReturn(319);
        JsonObject cell = firstCell(ready(filtered("surface", "all", "all", "all")));
        assertEquals(321, cell.get("y").getAsInt());
        assertUnknown(cell);
        cell.getAsJsonObject("technologies").entrySet().forEach(entry -> assertUnknown(entry.getValue().getAsJsonObject()));
    }

    @Test
    void unloadedReceiverIsUnknownAndNeverLoadsChunks() {
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(null);
        for (String height : List.of("surface", "64")) {
            JsonObject tile = ready(filtered(height, "all", "all", "all"));
            tile.getAsJsonArray("cells").forEach(cell -> assertUnknown(cell.getAsJsonObject()));
        }
        verify(chunks, times(8)).getChunkNow(anyInt(), anyInt());
        verifyNoMoreInteractions(chunks);
        verifyNoInteractions(chunk);
        verify(level, never()).getBlockState(any(BlockPos.class));
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    @Test
    void missingSourceOrIntermediateChunkIsUnknownDespiteLoadedReceiver() {
        antenna(new BlockPos(-32, 64, 32), G2_900);
        for (int missingX : new int[]{-2, -1}) {
            CoverageService.clear();
            when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);
            when(chunks.getChunkNow(missingX, 2)).thenReturn(null);
            assertUnknown(firstCell(ready(request(0))));
        }
        verify(chunks, atLeastOnce()).getChunkNow(anyInt(), anyInt());
        verifyNoMoreInteractions(chunks);
        verify(level, never()).getBlockState(any(BlockPos.class));
    }

    @Test
    void unknownCandidateDoesNotHideAConfirmedSignal() {
        BlockPos known = new BlockPos(31, 64, 32);
        antenna(known, G4_700);
        antenna(new BlockPos(-32, 64, 32), G5_700);
        for (int missingX : new int[]{-2, -1}) {
            CoverageService.clear();
            when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);
            when(chunks.getChunkNow(missingX, 2)).thenReturn(null);
            JsonObject cell = firstCell(ready(request(0)));
            assertEquals("signal", cell.get("state").getAsString());
            assertEquals("4G", cell.get("technology").getAsString());
            assertEquals(Long.toString(known.asLong()), cell.get("antenna").getAsString());
            JsonObject technologies = cell.getAsJsonObject("technologies");
            assertEquals("signal", technologies.getAsJsonObject("4G").get("state").getAsString());
            assertUnknown(technologies.getAsJsonObject("5G"));
            assertEquals("none", technologies.getAsJsonObject("2G").get("state").getAsString());
            assertEquals("none", technologies.getAsJsonObject("3G").get("state").getAsString());
            assertEquals(SignalPropagator.calculateSignal(level, known, new BlockPos(32, 64, 32), G4_700).powerDbm,
                    cell.get("powerDbm").getAsFloat(), 0.0001f);
        }
    }

    @Test
    void gameMapMatchesWebAndPhoneThroughWallPlacementRemovalAndMissingTerrain() {
        BlockPos source = new BlockPos(-32, 64, 32);
        antenna(source, G2_900);
        var request = new com.florentdubut.telecom.network.packet.RequestCoverageTilePayload(
                java.util.UUID.randomUUID(), "minecraft:overworld", 0, 0, 64, "64", "all", "2G", "G2_900", 0);
        List<Float> powers = new ArrayList<>();
        String previousModel = null;
        for (int phase = 0; phase < 3; phase++) {
            boolean wall = phase == 1;
            when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> {
                BlockPos pos = invocation.getArgument(0);
                return (wall && pos.getX() == -8 ? Blocks.STONE : Blocks.AIR).defaultBlockState();
            });
            CoverageService.invalidateChunk(level, -1, 2);
            JsonObject web = ready(request.request());
            var game = com.florentdubut.telecom.network.packet.CoverageTilePayload.fromSnapshot(request, web.toString());
            var cell = game.cells().getFirst();
            assertEquals(firstCell(web).get("powerDbm").getAsFloat(), cell.powerDbm());
            assertEquals(SignalPropagator.calculateSignal(level, source, new BlockPos(cell.x(), cell.y(), cell.z()), G2_900).powerDbm,
                    cell.powerDbm(), 0.0001f);
            assertEquals("unavailable", cell.service());
            assertEquals("signal", cell.state());
            assertNotEquals(previousModel, game.modelRevision());
            previousModel = game.modelRevision();
            powers.add(cell.powerDbm());
        }
        assertTrue(powers.get(1) < powers.getFirst());
        assertEquals(powers.getFirst(), powers.get(2));
        when(chunks.getChunkNow(-1, 2)).thenReturn(null);
        CoverageService.invalidateChunk(level, -1, 2);
        var unknown = com.florentdubut.telecom.network.packet.CoverageTilePayload.fromSnapshot(request, ready(request.request()).toString());
        assertEquals("unknown", unknown.cells().getFirst().state());
        assertEquals("unknown", unknown.cells().getFirst().service());
    }

    @Test
    void intermediateChunkInvalidationRecomputesTerrainButUnrelatedChunkKeepsRevision() {
        BlockPos source = new BlockPos(-32, 64, 32);
        antenna(source, G2_900);
        JsonObject air = ready(request(0));
        CoverageService.invalidateChunk(level, 500, 500);
        assertEquals(revision(air), revision(snapshot(request(0))));
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> {
            BlockPos pos = invocation.getArgument(0);
            return (pos.getX() == -8 ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        });
        // This chunk contains neither the antenna nor any tile cell center.
        CoverageService.invalidateChunk(level, -1, 2);
        JsonObject pending = snapshot(request(0));
        assertNotEquals(revision(air), revision(pending));
        assertEquals("pending", pending.get("status").getAsString());
        JsonObject wall = ready(request(0));
        float wallPower = firstCell(wall).get("powerDbm").getAsFloat();
        assertTrue(wallPower < firstCell(air).get("powerDbm").getAsFloat());
        assertEquals(SignalPropagator.calculateSignal(level, source, new BlockPos(32, 64, 32), G2_900).powerDbm,
                wallPower, 0.0001f);
    }

    @Test
    void loadingPreviouslyMissingIntermediateChunkInvalidatesUnknownResult() {
        antenna(new BlockPos(-32, 64, 32), G2_900);
        when(chunks.getChunkNow(-1, 2)).thenReturn(null);
        JsonObject unknown = ready(request(0));
        assertUnknown(firstCell(unknown));
        when(chunks.getChunkNow(-1, 2)).thenReturn(chunk);
        CoverageService.invalidateChunk(level, -1, 2);
        JsonObject loaded = ready(request(0));
        assertNotEquals(revision(unknown), revision(loaded));
        assertEquals("signal", firstCell(loaded).get("state").getAsString());
    }

    @Test
    void antennaMaskInvalidationChangesBandAndCanRemoveAllSignal() {
        NetworkNode antenna = antenna(new BlockPos(31, 64, 32), G2_900);
        JsonObject old = ready(request(0));
        assertEquals("G2_900", firstCell(old).get("band").getAsString());
        antenna.setFrequenciesMask(1 << G5_700.ordinal());
        CoverageService.invalidateAntennas(level);
        JsonObject updated = ready(request(0));
        assertNotEquals(revision(old), revision(updated));
        assertEquals("G5_700", firstCell(updated).get("band").getAsString());
        antenna.setFrequenciesMask(0);
        CoverageService.invalidateAntennas(level);
        JsonObject cell = firstCell(ready(request(0)));
        assertEquals("none", cell.get("state").getAsString());
        assertEquals("unavailable", cell.get("service").getAsString());
        assertTrue(cell.get("powerDbm").isJsonNull());
    }

    @Test
    void topologyChangesInvalidateReadyAndPendingJobsAndUpdateServiceReachability() {
        BlockPos source = new BlockPos(31, 64, 32);
        BlockPos router = source.west();
        BlockPos backend = router.west();
        antenna(source, G2_900);
        graph.addNode(new NetworkNode(router, NetworkNode.NodeType.ROUTER));
        graph.addNode(new NetworkNode(backend, NetworkNode.NodeType.SERVER));
        JsonObject disconnected = ready(request(0));
        assertEquals("unavailable", firstCell(disconnected).get("service").getAsString());
        graph.addEdge(new NetworkEdge(source, router, 1000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
        graph.addEdge(new NetworkEdge(router, backend, 1000, 1, NetworkEdge.EdgeType.FIBER, List.of()));
        JsonObject connected = ready(request(0));
        assertNotEquals(revision(disconnected), revision(connected));
        assertEquals("available", firstCell(connected).get("service").getAsString());
        String pendingRevision = revision(snapshot(request(1)));
        graph.removeEdgeBetween(router, backend);
        CoverageService.tickUntil(server, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2));
        assertNotEquals(pendingRevision, revision(snapshot(request(1))));
        JsonObject removed = ready(request(0));
        assertNotEquals(revision(connected), revision(removed));
        assertEquals("unavailable", firstCell(removed).get("service").getAsString());
        assertEquals(firstCell(connected).get("powerDbm"), firstCell(removed).get("powerDbm"));
    }

    @Test
    void filtersApplyAntennaTechnologyBandAndEnabledMaskBeforeSelection() {
        BlockPos near = new BlockPos(31, 64, 32);
        BlockPos far = new BlockPos(0, 64, 32);
        antenna(near, G2_900, G4_700);
        antenna(far, G5_700, G5_3500);
        assertEquals("5G", firstCell(ready(request(0))).get("technology").getAsString());
        assertEquals("G4_700", firstCell(ready(filtered("64", Long.toString(near.asLong()), "all", "all")))
                .get("band").getAsString());
        assertEquals("G2_900", firstCell(ready(filtered("64", "all", "2G", "all"))).get("band").getAsString());
        assertEquals("G5_3500", firstCell(ready(filtered("64", "all", "5G", "G5_3500"))).get("band").getAsString());
        for (var request : List.of(filtered("64", Long.toString(near.asLong()), "5G", "all"),
                filtered("64", "all", "4G", "G4_2600"), filtered("64", "123456789", "all", "all"))) {
            assertEquals("none", firstCell(ready(request)).get("state").getAsString());
        }
    }

    @Test
    void technologyAndBandSwitchesReusePhysicalJobWithoutAnyNewTerrainProbes() throws Exception {
        BlockPos source = new BlockPos(31, 64, 32);
        antenna(source, G2_900, G3_2100, G4_700, G5_700, G5_3500);
        var initial = filtered("64", "all", "4G", "all");
        String pending = revision(snapshot(initial));
        assertEquals(pending, revision(snapshot(filtered("64", "all", "5G", "G5_3500"))));
        JsonObject tile = ready(initial);
        Object physicalJob = storedJob(request(0));
        String model = CoverageService.modelRevision(level);
        JsonObject technologies = firstCell(tile).getAsJsonObject("technologies");
        assertEquals("4G", firstCell(tile).get("technology").getAsString());
        for (String technology : List.of("2G", "3G", "4G", "5G")) {
            assertEquals("signal", technologies.getAsJsonObject(technology).get("state").getAsString());
        }
        clearInvocations(chunk, chunks);
        for (TelecomFrequency frequency : TelecomFrequency.values()) {
            for (String band : List.of("all", frequency.name())) {
                JsonObject filtered = snapshot(filtered("64", "all", frequency.getTechnology(), band));
                assertEquals(pending, revision(filtered));
                assertEquals(model, filtered.get("modelRevision").getAsString());
                assertSame(physicalJob, storedJob(request(0)));
                assertEquals(frequency.getTechnology(), filtered.get("technologyFilter").getAsString());
                assertEquals(band, filtered.get("bandFilter").getAsString());
                if (band.equals("all")) {
                    JsonObject cell = firstCell(filtered);
                    assertEquals(technologies, cell.getAsJsonObject("technologies"));
                    for (String field : List.of("state", "powerDbm", "technology", "band", "antenna", "service")) {
                        assertEquals(technologies.getAsJsonObject(frequency.getTechnology()).get(field), cell.get(field));
                    }
                }
            }
        }
        assertEquals("G5_3500", firstCell(snapshot(filtered("64", "all", "all", "G5_3500"))).get("band").getAsString());
        assertEquals("5G", firstCell(snapshot(request(0))).get("technology").getAsString());
        CoverageService.tick(server);
        assertEquals(model, CoverageService.modelRevision(level));
        verifyNoInteractions(chunk, chunks);
    }

    @Test
    void twoGOnlyZoneIsRetainedEvenWhenInitialViewSelectsFiveG() {
        antenna(new BlockPos(31, 64, 32), G2_900);
        JsonObject fiveG = ready(filtered("64", "all", "5G", "all"));
        JsonObject cell = firstCell(fiveG);
        assertEquals("none", cell.get("state").getAsString());
        assertEquals("signal", cell.getAsJsonObject("technologies").getAsJsonObject("2G").get("state").getAsString());
        JsonObject twoG = snapshot(filtered("64", "all", "2G", "all"));
        assertEquals(revision(fiveG), revision(twoG));
        assertEquals("signal", firstCell(twoG).get("state").getAsString());
    }

    @Test
    void knownHitWinsUnknownWithinSameBandWhileOtherBandsRemainUnknown() {
        antenna(new BlockPos(31, 64, 32), G4_700);
        antenna(new BlockPos(-32, 64, 32), G4_700, G4_2600, G5_700);
        when(chunks.getChunkNow(-1, 2)).thenReturn(null);
        JsonObject tile = ready(request(0));
        JsonObject technologies = firstCell(tile).getAsJsonObject("technologies");
        assertEquals("signal", technologies.getAsJsonObject("4G").get("state").getAsString());
        assertUnknown(technologies.getAsJsonObject("5G"));
        assertUnknown(firstCell(snapshot(filtered("64", "all", "4G", "G4_2600"))));
        assertEquals("signal", firstCell(snapshot(filtered("64", "all", "4G", "G4_700"))).get("state").getAsString());
    }

    @Test
    void bandStoppedBelowFloorRemainsNoneWhenAnotherBandReachesMissingTerrain() {
        antenna(new BlockPos(-512, 64, 32), G2_900, G5_26000);
        when(chunks.getChunkNow(-1, 2)).thenReturn(null);
        JsonObject tile = ready(request(0));
        JsonObject technologies = firstCell(tile).getAsJsonObject("technologies");
        assertUnknown(technologies.getAsJsonObject("2G"));
        assertEquals("none", technologies.getAsJsonObject("5G").get("state").getAsString());
        assertEquals("unavailable", technologies.getAsJsonObject("5G").get("service").getAsString());
        assertEquals("none", firstCell(snapshot(filtered("64", "all", "5G", "G5_26000"))).get("state").getAsString());
    }

    @Test
    void oneMultiTracePerAntennaCellReceivesEveryActiveBandAndTheTickDeadline() {
        BlockPos source = new BlockPos(31, 64, 32);
        antenna(source, G2_900, G4_700, G5_700);
        List<TelecomFrequency> frequencies = List.of(G2_900, G4_700, G5_700);
        try (var traces = mockConstruction(SignalPropagator.MultiTrace.class, (trace, context) -> {
            assertEquals(source, context.arguments().get(0));
            assertEquals(frequencies, context.arguments().get(2));
            when(trace.advance(eq(level), eq(64), anyLong())).thenReturn(true);
            when(trace.results()).thenReturn(frequencies.stream()
                    .map(frequency -> new SignalPropagator.SignalResult(frequency, -70)).toList());
        })) {
            snapshot(filtered("64", "all", "4G", "all"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            CoverageService.tickUntil(server, deadline);
            assertEquals("ready", snapshot(request(0)).get("status").getAsString());
            assertEquals(4, traces.constructed().size(), "One trace per cell, not per band");
            traces.constructed().forEach(trace -> verify(trace).advance(level, 64, deadline));
            for (String technology : List.of("all", "2G", "3G", "4G", "5G")) {
                snapshot(filtered("64", "all", technology, "all"));
            }
            CoverageService.tick(server);
            assertEquals(4, traces.constructed().size(), "Filters must not construct more traces");
        }
    }

    @Test
    void workLimitFailsExplicitlyRatherThanPublishingPartialReadyCoverage() throws Exception {
        antenna(new BlockPos(0, 64, 32), G2_900, G5_700);
        snapshot(request(0));
        Object job = storedJob(request(0));
        var quanta = job.getClass().getDeclaredField("traceQuanta");
        quanta.setAccessible(true);
        quanta.setInt(job, 32768);
        CoverageService.tickUntil(server, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2));
        assertFalse(assertThrows(CoverageService.BusyException.class, () -> snapshot(request(0))).retryable());
        assertThrows(CoverageService.BusyException.class, () -> snapshot(filtered("64", "all", "5G", "all")));
    }

    @Test
    void chunkDependencyLimitFailsExplicitlyAndCannotBeBypassedWithFilters() {
        antenna(new BlockPos(0, 64, 32), G2_900, G5_700);
        var visited = java.util.stream.LongStream.range(0, 4097).boxed().collect(java.util.stream.Collectors.toSet());
        try (var traces = mockConstruction(SignalPropagator.MultiTrace.class, (trace, context) -> {
            when(trace.visitedChunks()).thenReturn(visited);
        })) {
            snapshot(request(0));
            CoverageService.tickUntil(server, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            assertEquals(1, traces.constructed().size());
            assertThrows(CoverageService.BusyException.class, () -> snapshot(request(0)));
            assertThrows(CoverageService.BusyException.class, () -> snapshot(filtered("64", "all", "5G", "all")));
        }
    }

    @Test
    void tiedAntennasChooseSamePackedPositionRegardlessOfInsertionOrder() {
        BlockPos first = new BlockPos(31, 64, 32);
        BlockPos second = new BlockPos(33, 64, 32);
        for (List<BlockPos> order : List.of(List.of(first, second), List.of(second, first))) {
            CoverageService.clear();
            graph = new TelecomNetworkGraph();
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            order.forEach(pos -> antenna(pos, G4_700));
            JsonObject cell = firstCell(ready(request(0)));
            assertEquals(Long.toString(first.asLong()), cell.get("antenna").getAsString());
        }
    }

    @Test
    void sourceLimitRejects65CandidatesButExplicitAntennaStillWorks() {
        for (int x = 0; x < 65; x++) antenna(new BlockPos(x, 64, 32), G2_900);
        assertThrows(CoverageService.BusyException.class, () -> snapshot(request(0)));
        assertThrows(CoverageService.BusyException.class, () -> snapshot(filtered("64", "all", "5G", "all")));
        JsonObject tile = ready(filtered("64", Long.toString(new BlockPos(0, 64, 32).asLong()), "all", "all"));
        assertEquals("signal", firstCell(tile).get("state").getAsString());
    }

    private NetworkNode antenna(BlockPos pos, TelecomFrequency... frequencies) {
        NetworkNode node = new NetworkNode(pos, NetworkNode.NodeType.ANTENNA);
        int mask = 0;
        for (TelecomFrequency frequency : frequencies) mask |= 1 << frequency.ordinal();
        node.setFrequenciesMask(mask);
        graph.addNode(node);
        return node;
    }

    @Test
    void entityRadioChangesInvalidateReadyAndPendingCoverageAndUseUpdatedTraceConfig() {
        BlockPos pos = new BlockPos(32, 64, 0);
        NetworkNode node = antenna(pos, G4_700);
        var entity = new com.florentdubut.telecom.block.entity.AntennaBlockEntity(pos,
                com.florentdubut.telecom.registry.ModBlocks.ANTENNA.get().defaultBlockState());
        entity.setEnabledFrequenciesMask(node.getFrequenciesMask());
        entity.setLevel(level);
        JsonObject old = ready(request(0));
        JsonObject pending = snapshot(request(1));
        String model = CoverageService.modelRevision(level);
        graph.setDirty(false);
        var config = new AntennaRadioConfig(1, 0, 0, 50, 50);
        entity.setRadioConfig(config);
        assertEquals(config, node.getRadioConfig());
        assertTrue(graph.isDirty());
        assertNotEquals(model, CoverageService.modelRevision(level));
        assertNotEquals(revision(pending), revision(snapshot(request(1))));
        JsonObject updated = ready(request(0));
        assertNotEquals(revision(old), revision(updated));
        float expected = SignalPropagator.calculateSignal(p -> SignalPropagator.Material.AIR, pos,
                new BlockPos(32, 64, 32), G4_700, config).powerDbm;
        assertEquals(expected, firstCell(updated).get("powerDbm").getAsFloat(), 1e-5);
        assertEquals(firstCell(old).get("powerDbm").getAsFloat() + 20, expected, 1e-5);
        verify(level).sendBlockUpdated(pos, entity.getBlockState(), entity.getBlockState(), 3);

        graph.setDirty(false);
        entity.setRadioConfig(config);
        assertFalse(graph.isDirty());
        assertEquals(revision(updated), revision(snapshot(request(0))));
        // Bandwidth is part of the source snapshot even though it does not change RF power.
        entity.setRadioConfig(new AntennaRadioConfig(1, 0, 0, 50, 25));
        assertNotEquals(revision(updated), revision(snapshot(request(0))));
    }

    private static CoverageService.Request request(int tileX) {
        return new CoverageService.Request(tileX, 0, 64, "64", "all", "all", "all");
    }

    private static CoverageService.Request filtered(String height, String antenna, String technology, String band) {
        return new CoverageService.Request(0, 0, 64, height, antenna, technology, band);
    }

    private JsonObject snapshot(CoverageService.Request request) {
        return JsonParser.parseString(CoverageService.request(level, request, Long.MAX_VALUE)).getAsJsonObject();
    }

    private Object storedJob(CoverageService.Request request) throws Exception {
        var states = CoverageService.class.getDeclaredField("STATES");
        states.setAccessible(true);
        Object state = ((Map<?, ?>) states.get(null)).get(level);
        var entries = state.getClass().getDeclaredField("entries");
        entries.setAccessible(true);
        return ((Map<?, ?>) entries.get(state)).get(request);
    }

    private JsonObject ready(CoverageService.Request request) {
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        JsonObject tile = snapshot(request);
        while (!tile.get("status").getAsString().equals("ready")) {
            assertTrue(System.nanoTime() < timeout, "Coverage did not finish within bounded ticks: " + tile);
            CoverageService.tickUntil(server, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2));
            tile = snapshot(request);
        }
        return tile;
    }

    private static String revision(JsonObject tile) {
        return tile.get("revision").getAsString();
    }

    private static JsonObject firstCell(JsonObject tile) {
        return tile.getAsJsonArray("cells").get(0).getAsJsonObject();
    }

    private static void assertUnknown(JsonObject cell) {
        assertEquals("unknown", cell.get("state").getAsString());
        assertEquals("unknown", cell.get("service").getAsString());
        for (String key : List.of("powerDbm", "technology", "band", "antenna")) assertTrue(cell.get(key).isJsonNull(), key);
    }
}
