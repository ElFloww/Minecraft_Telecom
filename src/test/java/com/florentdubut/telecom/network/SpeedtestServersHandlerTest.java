package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.entity.RouterBlockEntity;
import com.florentdubut.telecom.network.packet.RequestSpeedtestServersPayload;
import com.florentdubut.telecom.network.packet.SpeedtestServersPayload;
import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import com.florentdubut.telecom.network.packet.StartSpeedtestPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeedtestServersHandlerTest {
    private static final BlockPos POS = new BlockPos(3, 80, 4);
    private ServerPlayer player;
    private ServerLevel level;
    private ServerChunkCache chunks;
    private TelecomNetworkGraph graph;
    private IPayloadContext context;
    private MockedStatic<TelecomNetworkGraph> graphs;

    @BeforeEach
    void setUp() {
        player = mock(ServerPlayer.class);
        level = mock(ServerLevel.class);
        chunks = mock(ServerChunkCache.class);
        graph = mock(TelecomNetworkGraph.class);
        context = mock(IPayloadContext.class);
        when(context.player()).thenReturn(player);
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(new UUID(1, 2));
        when(player.isWithinBlockInteractionRange(POS, 0)).thenReturn(true);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.isInWorldBounds(POS)).thenReturn(true);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(0, 0)).thenReturn(mock(LevelChunk.class));
        var router = mock(RouterBlockEntity.class);
        when(router.getConfiguredMaxDown()).thenReturn(900);
        when(router.getConfiguredMaxUp()).thenReturn(300);
        when(level.getBlockEntity(POS)).thenReturn(router);
        NetworkNode node = new NetworkNode(POS, NetworkNode.NodeType.ROUTER);
        node.setIpAddress("192.168.0.2");
        when(graph.getNode(POS)).thenReturn(node);
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return CompletableFuture.completedFuture(null);
        }).when(context).enqueueWork(any(Runnable.class));
        graphs = mockStatic(TelecomNetworkGraph.class);
        graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
    }

    @AfterEach
    void tearDown() { graphs.close(); }

    @Test
    void catalogueIsCorrelatedBoundedAndRateLimited() throws Exception {
        var request = request(false, "minecraft:overworld");
        var options = IntStream.range(0, 128).mapToObj(i -> new SpeedtestServerOption("" + i, "Server", i, true, 1000, "")).toList();
        when(graph.getSpeedtestServers(POS, 0)).thenReturn(options);
        when(graph.getNodes()).thenReturn(IntStream.range(0, 129)
                .mapToObj(i -> new NetworkNode(new BlockPos(i, 64, 0), NetworkNode.NodeType.SERVER)).toList());
        handle("handleRequestSpeedtestServers", request);
        handle("handleRequestSpeedtestServers", request);
        verify(graph, times(1)).getSpeedtestServers(POS, 0);
        verify(context, times(1)).reply(argThat(value -> value instanceof SpeedtestServersPayload payload
                && payload.requestId().equals(request.requestId()) && payload.dimension().equals(request.dimension())
                && payload.deviceId().equals(TrafficSession.routerDeviceId(POS)) && payload.truncated() && payload.servers().equals(options)));
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    @Test
    void wrongDimensionDoesNotReadGraph() throws Exception {
        handle("handleRequestSpeedtestServers", request(false, "minecraft:the_nether"));
        graphs.verifyNoInteractions();
        verify(context, never()).reply(any());
    }

    @Test
    void unloadedRouterDoesNotLoadChunkOrReadBlockEntity() throws Exception {
        when(chunks.getChunkNow(0, 0)).thenReturn(null);
        handle("handleRequestSpeedtestServers", request(false, "minecraft:overworld"));
        verify(graph, never()).getSpeedtestServers(any(), anyInt());
        verify(level, never()).getBlockEntity(any());
        verify(level, never()).getChunk(anyInt(), anyInt());
        verify(context).reply(argThat(value -> value instanceof SpeedtestServersPayload payload && payload.errorCode().equals("invalid_request")));
    }

    @Test
    void remoteRouterIsRefusedEvenIfLoaded() throws Exception {
        when(player.isWithinBlockInteractionRange(POS, 0)).thenReturn(false);
        handle("handleRequestSpeedtestServers", request(false, "minecraft:overworld"));
        verify(graph, never()).getSpeedtestServers(any(), anyInt());
        verify(level, never()).getBlockEntity(any());
    }

    @Test
    void explicitMobileContextCannotUseRouterWithoutSmartphone() throws Exception {
        when(player.getInventory()).thenReturn(mock(Inventory.class));
        handle("handleRequestSpeedtestServers", request(true, "minecraft:overworld"));
        verify(graph, never()).getSpeedtestServers(any(), anyInt());
        verify(context).reply(argThat(value -> value instanceof SpeedtestServersPayload payload
                && payload.deviceId().equals(TrafficSession.mobileDeviceId(player.getUUID())) && payload.errorCode().equals("invalid_request")));
    }

    @ParameterizedTest
    @CsvSource({"G5_700,15", "G4_700,40", "G3_900,95", "G2_900,300"})
    void mobileRescansAuthoritativeAntennaAndUsesEstimatedRadioPing(TelecomFrequency frequency, int ping) throws Exception {
        var inventory = mock(Inventory.class);
        var phone = mock(net.minecraft.world.item.ItemStack.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContainerSize()).thenReturn(1);
        when(inventory.getItem(0)).thenReturn(phone);
        when(phone.is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get())).thenReturn(true);
        when(player.blockPosition()).thenReturn(POS);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(319);
        BlockPos actualAntenna = POS.above();
        when(level.isInWorldBounds(actualAntenna)).thenReturn(true);
        var antenna = mock(com.florentdubut.telecom.block.entity.AntennaBlockEntity.class);
        when(antenna.isFrequencyEnabled(frequency)).thenReturn(true);
        when(antenna.getAntennaName()).thenReturn("Actual antenna");
        when(level.getBlockEntity(actualAntenna)).thenReturn(antenna);
        var node = new NetworkNode(actualAntenna, NetworkNode.NodeType.ANTENNA);
        node.setFrequenciesMask(1 << frequency.ordinal());
        when(graph.getNodes()).thenReturn(List.of(node));
        when(graph.getMobileIp(player.getUUID())).thenReturn("10.0.0.2");
        when(graph.getSpeedtestServers(actualAntenna, ping)).thenReturn(List.of());
        handle("handleRequestSpeedtestServers", request(true, "minecraft:overworld"));
        verify(graph).getSpeedtestServers(actualAntenna, ping);
        verify(graph, never()).getSpeedtestServers(eq(POS), anyInt());
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    @Test
    void busyStartAcknowledgesExactErrorOnceWithoutFetchingOldSession() throws Exception {
        var request = new StartSpeedtestPayload(POS, "forged", 1, 1, 9999, 0, 300, "42", "minecraft:overworld");
        when(graph.startSpeedtest(POS, "192.168.0.2", 900, 300, 0, 0, 300, false, player, "42"))
                .thenReturn(new TelecomNetworkGraph.SpeedtestStartResult(null, "device_busy"));
        try (var packets = mockStatic(PacketDistributor.class)) {
            handle("handleStartSpeedtest", request);
            packets.verify(() -> PacketDistributor.sendToPlayer(eq(player), argThat(value -> value instanceof SpeedtestUpdatePayload payload
                    && payload.state().equals("REJECTED") && payload.errorCode().equals("device_busy") && payload.serverId().equals("42")
                    && payload.sessionId().equals(new UUID(0, 0)))), times(1));
            packets.verifyNoMoreInteractions();
        }
        verify(graph, never()).getSessionByDeviceId(anyString());
    }

    @Test
    void acceptedStartAndManualSyncIncludeDestinationMetadata() throws Exception {
        var session = new TrafficSession(POS, POS.above(), "192.168.0.2", 900, 300, 300, false, TrafficSession.routerDeviceId(POS));
        when(graph.startSpeedtest(POS, "192.168.0.2", 900, 300, 0, 0, 300, false, player, ""))
                .thenReturn(new TelecomNetworkGraph.SpeedtestStartResult(session, ""));
        try (var packets = mockStatic(PacketDistributor.class)) {
            handle("handleStartSpeedtest", new StartSpeedtestPayload(POS, "forged", 1, 1, 0, 0, 300, "", "minecraft:overworld"));
            packets.verify(() -> PacketDistributor.sendToPlayer(eq(player), argThat(value -> value instanceof SpeedtestUpdatePayload payload
                    && payload.sessionId().equals(session.getSessionId()) && payload.serverId().equals(session.getServerId())
                    && payload.serverName().equals(session.getServerName()) && payload.errorCode().isEmpty())), times(1));
            packets.verifyNoMoreInteractions();
            packets.clearInvocations();
            var sync = ModNetworking.class.getDeclaredMethod("sendSpeedtestState", ServerPlayer.class, TrafficSession.class, String.class, String.class);
            sync.setAccessible(true);
            sync.invoke(null, player, session, session.getDeviceId(), session.getClientIp());
            packets.verify(() -> PacketDistributor.sendToPlayer(eq(player), argThat(value -> value instanceof SpeedtestUpdatePayload payload
                    && payload.serverId().equals(session.getServerId()) && payload.serverName().equals(session.getServerName()))));
        }
    }

    @Test
    void destroyedRouterNeverFallsBackToPhone() throws Exception {
        when(graph.getNode(POS)).thenReturn(null);
        try (var packets = mockStatic(PacketDistributor.class)) {
            handle("handleStartSpeedtest", new StartSpeedtestPayload(POS, "", 1, 1, 0, 0, 300, "42", "minecraft:overworld"));
            packets.verify(() -> PacketDistributor.sendToPlayer(eq(player), argThat(value -> value instanceof SpeedtestUpdatePayload payload
                    && payload.deviceId().equals(TrafficSession.routerDeviceId(POS)) && payload.errorCode().equals("invalid_request"))));
        }
        verify(player, never()).getInventory();
    }

    @ParameterizedTest
    @CsvSource({"minecraft:overworld,0", "minecraft:overworld,1", "'',0", "'',1"})
    void rejectsEmptyOrOldDimensionBeforeResolvingSameCoordinatesInNewWorld(String requestedDimension, int bands) throws Exception {
        when(level.dimension()).thenReturn(Level.NETHER);
        BlockPos serverPos = POS.above();
        when(graph.getNode(serverPos)).thenReturn(new NetworkNode(serverPos, NetworkNode.NodeType.SERVER));
        try (var packets = mockStatic(PacketDistributor.class)) {
            handle("handleStartSpeedtest", new StartSpeedtestPayload(POS, "192.168.0.2", 900, 300, 0, bands, 300,
                    Long.toString(serverPos.asLong()), requestedDimension));
            verify(context).reply(argThat(value -> value instanceof SpeedtestUpdatePayload payload
                    && payload.state().equals("REJECTED") && payload.errorCode().equals("invalid_request")
                    && payload.dimension().equals(requestedDimension) && payload.sessionId().equals(new UUID(0, 0))));
            packets.verifyNoInteractions();
        }
        graphs.verifyNoInteractions();
        verifyNoInteractions(graph);
        verify(level, never()).getBlockEntity(any());
        verify(player, never()).getInventory();
    }

    @Test
    void catalogueLimitReturnsCorrelatedErrorWithoutDisconnectOrPartialList() throws Exception {
        var request = request(false, "minecraft:overworld");
        when(graph.getSpeedtestServers(POS, 0)).thenThrow(TelecomNetworkGraph.SpeedtestCatalogueLimitException.class);
        handle("handleRequestSpeedtestServers", request);
        verify(context).reply(argThat(value -> value instanceof SpeedtestServersPayload payload
                && payload.requestId().equals(request.requestId()) && payload.dimension().equals(request.dimension())
                && payload.deviceId().equals(TrafficSession.routerDeviceId(POS)) && payload.servers().isEmpty()
                && !payload.truncated() && payload.errorCode().equals("catalogue_limit")));
        verify(graph, never()).getNodes();
    }

    private RequestSpeedtestServersPayload request(boolean mobile, String dimension) {
        return new RequestSpeedtestServersPayload(mobile, POS, UUID.randomUUID(), dimension);
    }

    private void handle(String name, Object payload) throws Exception {
        var method = ModNetworking.class.getDeclaredMethod(name, payload.getClass(), IPayloadContext.class);
        method.setAccessible(true);
        method.invoke(null, payload, context);
    }
}
