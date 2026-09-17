package com.florentdubut.telecom.network;

import com.florentdubut.telecom.network.packet.CoverageTilePayload;
import com.florentdubut.telecom.network.packet.RequestCoverageTilePayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoverageTileHandlerTest {
    private ServerPlayer player;
    private ServerLevel level;
    private IPayloadContext context;

    @BeforeEach
    void setUp() {
        player = mock(ServerPlayer.class);
        level = mock(ServerLevel.class);
        context = mock(IPayloadContext.class);
        when(context.player()).thenReturn(player);
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return CompletableFuture.completedFuture(null);
        }).when(context).enqueueWork(any(Runnable.class));
    }

    @Test
    void readsSharedSnapshotOnServerWorkAndThrottlesRepeatedRequests() throws Exception {
        var request = request("minecraft:overworld", 0);
        try (var coverage = mockStatic(CoverageService.class)) {
            coverage.when(() -> CoverageService.request(eq(level), eq(request.request()), anyLong()))
                    .thenReturn("{\"modelRevision\":\"model:1\",\"status\":\"pending\",\"progress\":0,\"cells\":[]}");
            handle(request);
            handle(request);
            coverage.verify(() -> CoverageService.request(eq(level), eq(request.request()), anyLong()), times(1));
            verify(context, times(1)).reply(argThat(payload -> payload instanceof CoverageTilePayload tile
                    && tile.viewId().equals(request.viewId()) && tile.status().equals("pending")));
            verify(context, times(2)).enqueueWork(any(Runnable.class));
        }
    }

    @Test
    void doesNotReadPlayerOrWorldUntilTheServerWorkRuns() throws Exception {
        doReturn(CompletableFuture.completedFuture(null)).when(context).enqueueWork(any(Runnable.class));
        var request = request("minecraft:overworld", 0);
        try (var coverage = mockStatic(CoverageService.class)) {
            coverage.when(() -> CoverageService.request(eq(level), eq(request.request()), anyLong()))
                    .thenReturn("{\"modelRevision\":\"model:1\",\"status\":\"pending\",\"progress\":0,\"cells\":[]}");
            handle(request);
            coverage.verifyNoInteractions();
            verify(context, never()).player();
            var work = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(context).enqueueWork(work.capture());
            work.getValue().run();
            coverage.verify(() -> CoverageService.request(eq(level), eq(request.request()), anyLong()));
        }
    }

    @Test
    void simultaneousPlayersHaveIndependentRequestCooldowns() throws Exception {
        var request = request("minecraft:overworld", 0);
        ServerPlayer other = mock(ServerPlayer.class);
        when(other.level()).thenReturn(level);
        try (var coverage = mockStatic(CoverageService.class)) {
            coverage.when(() -> CoverageService.request(eq(level), eq(request.request()), anyLong()))
                    .thenReturn("{\"modelRevision\":\"model:1\",\"status\":\"pending\",\"progress\":0,\"cells\":[]}");
            handle(request);
            when(context.player()).thenReturn(other);
            handle(request);
            coverage.verify(() -> CoverageService.request(eq(level), eq(request.request()), anyLong()), times(2));
            verify(context, times(2)).reply(any(CoverageTilePayload.class));
        }
    }

    @Test
    void rejectsRequestsForAnotherDimensionBeforeQueryingCoverage() throws Exception {
        try (var coverage = mockStatic(CoverageService.class)) {
            handle(request("minecraft:the_nether", 0));
            coverage.verifyNoInteractions();
            verify(context, never()).reply(any());
        }
    }

    @Test
    void rejectsDisconnectedPlayerBeforeQueryingCoverage() throws Exception {
        when(player.hasDisconnected()).thenReturn(true);
        try (var coverage = mockStatic(CoverageService.class)) {
            handle(request("minecraft:overworld", 0));
            coverage.verifyNoInteractions();
            verify(context, never()).reply(any());
        }
    }

    @Test
    void invalidExtentReturnsExplicitFailureWithoutSchedulingWork() throws Exception {
        try (var coverage = mockStatic(CoverageService.class)) {
            handle(request("minecraft:overworld", Integer.MAX_VALUE));
            coverage.verifyNoInteractions();
            verify(context).reply(argThat(payload -> payload instanceof CoverageTilePayload tile
                    && tile.status().equals("invalid") && tile.cells().isEmpty()));
        }
    }

    @Test
    void fullQueueReturnsRetryableStatusInsteadOfDisconnectingPlayer() throws Exception {
        var request = request("minecraft:overworld", 0);
        try (var coverage = mockStatic(CoverageService.class)) {
            coverage.when(() -> CoverageService.request(eq(level), eq(request.request()), anyLong()))
                    .thenThrow(new CoverageService.BusyException("Queue full"));
            handle(request);
            verify(context).reply(argThat(payload -> payload instanceof CoverageTilePayload tile
                    && tile.status().equals("busy") && tile.cells().isEmpty()));
        }
    }

    private RequestCoverageTilePayload request(String dimension, int tileX) {
        return new RequestCoverageTilePayload(UUID.randomUUID(), dimension, tileX, 0, 16,
                "surface", "all", "all", "all", 0);
    }

    @Test
    void permanentWorkLimitIsNotReportedAsTemporaryCongestion() throws Exception {
        var request = request("minecraft:overworld", 0);
        try (var coverage = mockStatic(CoverageService.class)) {
            coverage.when(() -> CoverageService.request(eq(level), eq(request.request()), anyLong()))
                    .thenThrow(new CoverageService.BusyException("Work limit exceeded", false));
            handle(request);
            verify(context).reply(argThat(payload -> payload instanceof CoverageTilePayload tile
                    && tile.status().equals("limited") && tile.cells().isEmpty()));
        }
    }

    private void handle(RequestCoverageTilePayload request) throws Exception {
        var method = ModNetworking.class.getDeclaredMethod("handleRequestCoverageTile", RequestCoverageTilePayload.class, IPayloadContext.class);
        method.setAccessible(true);
        assertDoesNotThrow(() -> method.invoke(null, request, context));
    }
}
