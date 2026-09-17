package com.florentdubut.telecom.server;

import com.florentdubut.telecom.network.CoverageService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ZoneJobManagerTest {
    private MinecraftServer server;
    private ServerLevel level;
    private ServerChunkCache chunks;
    private TerrainCaptureQueue captures;
    private ZoneJobManager manager;
    private CompletableFuture<ChunkResult<ChunkAccess>> generation;
    private CompletableFuture<Boolean> saved;
    private AtomicInteger observations;

    @BeforeEach
    void setup() {
        server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);
        chunks = mock(ServerChunkCache.class);
        captures = mock(TerrainCaptureQueue.class);
        when(server.overworld()).thenReturn(level);
        when(level.getChunkSource()).thenReturn(chunks);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(319);
        generation = new CompletableFuture<>();
        saved = new CompletableFuture<>();
        when(chunks.getChunkFuture(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(true))).thenReturn(generation);
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(mock(LevelChunk.class));
        when(captures.captureAsync(anyInt(), anyInt())).thenReturn(saved);
        observations = new AtomicInteger();
        manager = new ZoneJobManager(server, captures, (world, chunk) -> observations.incrementAndGet());
    }

    private ZoneJobManager.Request terrain(int minX, int minZ, int maxX, int maxZ) {
        return new ZoneJobManager.Request("terrain", minX, minZ, maxX, maxZ, 16, "surface", "all", "all", "all");
    }

    private JsonObject job() { return JsonParser.parseString(manager.status()).getAsJsonObject().getAsJsonObject("job"); }

    @Test
    void validatesBoundsAndBudgetsWithoutTouchingTheWorld() {
        assertEquals(4096, terrain(0, 0, 1023, 1023).total());
        assertEquals(4096, terrain(-1024, -1024, -1, -1).total());
        assertEquals(4, terrain(-1, -1, 0, 0).total());
        assertThrows(IllegalArgumentException.class, () -> terrain(0, 0, 65536, 15));
        assertThrows(IllegalArgumentException.class, () -> terrain(-30_000_001, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> terrain(1, 0, 0, 0));
        for (int step : new int[]{1, 8, 16}) {
            int side = 32 * 16 * step;
            var request = new ZoneJobManager.Request("coverage", -side, -side, -1, -1, step, "surface", "all", "all", "all");
            assertEquals(1024, request.total());
            assertThrows(IllegalArgumentException.class, () -> new ZoneJobManager.Request("coverage", 0, 0,
                    1024 * 16 * step, 16 * step - 1, step, "surface", "all", "all", "all"));
        }
        assertThrows(IllegalArgumentException.class, () -> terrain(-29_999_984, -29_999_984, 29_999_999, 29_999_999));
        verifyNoInteractions(chunks, captures);
    }

    @Test
    void largeTerrainJobCompletesBeyondTheOldLimitWithoutParallelGeneration() {
        generation.complete(ChunkResult.of(mock(LevelChunk.class)));
        saved.complete(true);
        manager.start(terrain(0, 0, 1023, 1023));
        for (int chunk = 0; chunk < 4096; chunk++) {
            manager.tick();
            manager.tick();
            manager.tick();
        }
        JsonObject result = job();
        assertEquals("completed", result.get("state").getAsString());
        assertEquals(4096, result.get("completed").getAsInt());
        assertEquals(1.0, result.get("progress").getAsDouble());
        verify(chunks, times(4096)).getChunkFuture(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(true));
        verify(chunks, times(4096)).removeTicketWithRadius(any(TicketType.class), any(ChunkPos.class), eq(0));
        assertEquals(4096, observations.get());
    }

    @Test
    void largeCoverageJobVisitsEveryTileWithTheRequestedResolution() {
        try (var coverage = mockStatic(CoverageService.class)) {
            coverage.when(() -> CoverageService.request(eq(level), any(), anyLong()))
                    .thenReturn("{\"status\":\"ready\",\"cells\":[]}");
            manager.start(new ZoneJobManager.Request("coverage", -8192, -8192, -1, -1, 16, "64", "all", "4G", "all"));
            for (int i = 0; i < 1024; i++) manager.tick();
            JsonObject result = job();
            assertEquals("completed", result.get("state").getAsString());
            assertEquals(1024, result.get("completed").getAsInt());
            assertEquals(1024, result.getAsJsonArray("coverageTiles").size());
            var last = result.getAsJsonArray("coverageTiles").get(1023).getAsJsonObject();
            assertEquals(-1, last.get("x").getAsInt());
            assertEquals(-1, last.get("z").getAsInt());
            assertEquals(16, last.get("step").getAsInt());
            coverage.verify(() -> CoverageService.request(eq(level), any(), anyLong()), times(1024));
            verifyNoInteractions(chunks, captures);
        }
    }

    @Test
    void generatesOneChunkAtATimeAndWaitsForTheSavedPng() {
        manager.start(terrain(-16, 0, 15, 15));
        verifyNoInteractions(chunks, captures);
        manager.tick();
        for (int i = 0; i < 30; i++) manager.tick();
        verify(chunks, times(1)).getChunkFuture(-1, 0, ChunkStatus.FULL, true);
        assertEquals(0, job().get("completed").getAsInt());
        generation.complete(ChunkResult.of(mock(LevelChunk.class)));
        manager.tick();
        assertEquals(1, observations.get());
        verify(captures).captureAsync(-1, 0);
        manager.tick();
        assertEquals(0, job().get("completed").getAsInt());
        saved.complete(true);
        manager.tick();
        assertEquals(1, job().get("completed").getAsInt());
        verify(chunks).removeTicketWithRadius(any(TicketType.class), eq(new ChunkPos(-1, 0)), eq(0));
        manager.tick();
        verify(chunks).getChunkFuture(0, 0, ChunkStatus.FULL, true);
        verify(chunks, never()).updateChunkForced(any(), anyBoolean());
        manager.close();
    }

    @Test
    void cancelReleasesOnlyItsTicketAndNeverContinuesGeneration() {
        String id = manager.start(terrain(0, 0, 31, 15));
        assertThrows(IllegalStateException.class, () -> manager.start(terrain(0, 0, 0, 0)));
        assertFalse(manager.cancel("another-job"));
        manager.tick();
        assertTrue(manager.cancel(id));
        assertFalse(generation.isCancelled(), "Do not cancel a future shared with vanilla or other consumers");
        generation.complete(ChunkResult.of(mock(LevelChunk.class)));
        for (int i = 0; i < 10; i++) manager.tick();
        assertEquals("cancelled", job().get("state").getAsString());
        verify(chunks, times(1)).getChunkFuture(anyInt(), anyInt(), any(), anyBoolean());
        verify(chunks, times(1)).removeTicketWithRadius(any(), eq(new ChunkPos(0, 0)), eq(0));
        verifyNoInteractions(captures);
    }

    @Test
    void failuresAndCloseReleaseRetainedChunks() {
        manager.start(terrain(0, 0, 0, 0));
        manager.tick();
        generation.complete(ChunkResult.error("generation failed"));
        manager.tick();
        assertEquals("failed", job().get("state").getAsString());
        verify(chunks).removeTicketWithRadius(any(), any(), eq(0));
        manager.close();
        assertThrows(IllegalStateException.class, () -> manager.start(terrain(0, 0, 0, 0)));
    }

    @Test
    void absentCaptureIsAFailureNotFalseCompletion() {
        manager.start(terrain(0, 0, 0, 0));
        manager.tick();
        generation.complete(ChunkResult.of(mock(LevelChunk.class)));
        manager.tick();
        saved.complete(false);
        manager.tick();
        assertEquals("failed", job().get("state").getAsString());
        assertEquals(0, job().get("completed").getAsInt());
    }

    @Test
    void coverageUsesTheExactGridWithoutGeneratingTerrain() {
        try (var coverage = mockStatic(CoverageService.class)) {
            coverage.when(() -> CoverageService.request(eq(level), any(), anyLong())).thenReturn(
                    "{\"status\":\"pending\"}", "{\"status\":\"ready\",\"cells\":[{\"state\":\"unknown\"}]}");
            manager.start(new ZoneJobManager.Request("coverage", -16, 0, -1, 15, 1, "64", "all", "4G", "all"));
            manager.tick();
            assertEquals(0, job().get("completed").getAsInt());
            manager.tick();
            assertEquals("completed", job().get("state").getAsString());
            assertEquals(1, job().get("unknownPoints").getAsInt());
            assertEquals(-3, job().getAsJsonArray("coverageTiles").get(0).getAsJsonObject().get("level").getAsInt());
            coverage.verify(() -> CoverageService.invalidateArea(level, -16, 0, -1, 15));
            verifyNoInteractions(chunks, captures);
        }
    }
}
