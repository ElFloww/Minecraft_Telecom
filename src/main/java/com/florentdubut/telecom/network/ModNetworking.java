package com.florentdubut.telecom.network;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.block.entity.RouterBlockEntity;
import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;
import com.florentdubut.telecom.network.packet.RequestCoverageTilePayload;
import com.florentdubut.telecom.network.packet.CoverageTilePayload;
import com.florentdubut.telecom.network.packet.RequestSpeedtestServersPayload;
import com.florentdubut.telecom.network.packet.SpeedtestServersPayload;
import com.florentdubut.telecom.network.packet.RequestServerRefreshPayload;
import com.florentdubut.telecom.network.packet.ServerGuiSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = TelecomMod.MODID)
public class ModNetworking {

    private enum RequestCategory {
        ANTENNA_CONFIG, GUI_REFRESH, SERVER_REFRESH, ANTENNA_REFRESH, TOOL_REFRESH, MAP, COVERAGE, SPEEDTEST, SPEEDTEST_SERVERS, NPERF, SCAN
    }

    // Server-thread only. Values never retain players; each player has a fixed-size table.
    private static final java.util.Map<ServerPlayer, long[]> REQUEST_COOLDOWNS = new java.util.WeakHashMap<>();
    private static final int VALID_FREQUENCIES_MASK = (1 << TelecomFrequency.values().length) - 1;

    private static boolean acceptRequest(ServerPlayer player, RequestCategory category, int cooldownMillis) {
        if (player.hasDisconnected()) return false;
        long[] deadlines = REQUEST_COOLDOWNS.computeIfAbsent(player, ignored -> new long[RequestCategory.values().length]);
        long now = System.nanoTime();
        int index = category.ordinal();
        if (deadlines[index] != 0 && now - deadlines[index] < 0) return false;
        deadlines[index] = now + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(cooldownMillis);
        return true;
    }

    private static boolean isLoaded(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    private static boolean hasSmartphone(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get())) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1.4");

        registrar.playToServer(RequestServerRefreshPayload.TYPE, RequestServerRefreshPayload.STREAM_CODEC,
                ModNetworking::handleServerRefreshRequest);

        registrar.playToServer(RequestSpeedtestServersPayload.TYPE, RequestSpeedtestServersPayload.STREAM_CODEC,
                ModNetworking::handleRequestSpeedtestServers);
        registrar.playToClient(SpeedtestServersPayload.TYPE, SpeedtestServersPayload.STREAM_CODEC,
                ModNetworking::handleSpeedtestServers);

        registrar.playToServer(RequestCoverageTilePayload.TYPE, RequestCoverageTilePayload.STREAM_CODEC,
                ModNetworking::handleRequestCoverageTile);
        registrar.playToClient(CoverageTilePayload.TYPE, CoverageTilePayload.STREAM_CODEC,
                ModNetworking::handleCoverageTile);

        registrar.playToServer(
            com.florentdubut.telecom.network.packet.GuiRefreshRequestPayload.TYPE,
            com.florentdubut.telecom.network.packet.GuiRefreshRequestPayload.STREAM_CODEC,
            ModNetworking::handleGuiRefreshRequest
        );

        registrar.playToServer(
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.TYPE,
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.STREAM_CODEC,
            ModNetworking::handleToggleNperf
        );

        registrar.playToServer(
            AntennaConfigPayload.TYPE,
            AntennaConfigPayload.STREAM_CODEC,
            ModNetworking::handleAntennaConfig
        );

        registrar.playToServer(
            com.florentdubut.telecom.network.packet.RouterConfigPayload.TYPE,
            com.florentdubut.telecom.network.packet.RouterConfigPayload.STREAM_CODEC,
            ModNetworking::handleRouterConfig
        );

        registrar.playToServer(
            com.florentdubut.telecom.network.packet.StartSpeedtestPayload.TYPE,
            com.florentdubut.telecom.network.packet.StartSpeedtestPayload.STREAM_CODEC,
            ModNetworking::handleStartSpeedtest
        );

        registrar.playToServer(
            com.florentdubut.telecom.network.packet.NetworkToolRefreshRequestPayload.TYPE,
            com.florentdubut.telecom.network.packet.NetworkToolRefreshRequestPayload.STREAM_CODEC,
            ModNetworking::handleNetworkToolRefreshRequest
        );

        registrar.playToClient(
            com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload.TYPE,
            com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload.STREAM_CODEC,
            ModNetworking::handleSpeedtestUpdate
        );

        registrar.playToServer(
            com.florentdubut.telecom.network.packet.RequestNetworkMapPayload.TYPE,
            com.florentdubut.telecom.network.packet.RequestNetworkMapPayload.STREAM_CODEC,
            ModNetworking::handleRequestNetworkMap
        );

        registrar.playToClient(
            com.florentdubut.telecom.network.packet.NetworkMapResponsePayload.TYPE,
            com.florentdubut.telecom.network.packet.NetworkMapResponsePayload.STREAM_CODEC,
            ModNetworking::handleNetworkMapResponse
        );

        registrar.playToClient(
            NetworkScanResponsePayload.TYPE,
            NetworkScanResponsePayload.STREAM_CODEC,
            ModNetworking::handleNetworkScanResponse
        );

        registrar.playToClient(
            com.florentdubut.telecom.network.packet.RouterGuiSyncPayload.TYPE,
            com.florentdubut.telecom.network.packet.RouterGuiSyncPayload.STREAM_CODEC,
            ModNetworking::handleRouterGuiSync
        );

        registrar.playToClient(
            com.florentdubut.telecom.network.packet.ServerGuiSyncPayload.TYPE,
            com.florentdubut.telecom.network.packet.ServerGuiSyncPayload.STREAM_CODEC,
            ModNetworking::handleServerGuiSync
        );

        registrar.playToClient(
            com.florentdubut.telecom.network.packet.NetworkToolSyncPayload.TYPE,
            com.florentdubut.telecom.network.packet.NetworkToolSyncPayload.STREAM_CODEC,
            ModNetworking::handleNetworkToolSync
        );

        registrar.playToClient(
            com.florentdubut.telecom.network.packet.ServerBandwidthUpdatePayload.TYPE,
            com.florentdubut.telecom.network.packet.ServerBandwidthUpdatePayload.STREAM_CODEC,
            ModNetworking::handleServerBandwidthUpdate
        );

        registrar.playToClient(
            com.florentdubut.telecom.network.packet.AntennaGuiSyncPayload.TYPE,
            com.florentdubut.telecom.network.packet.AntennaGuiSyncPayload.STREAM_CODEC,
            ModNetworking::handleAntennaGuiSync
        );

        registrar.playToServer(
            com.florentdubut.telecom.network.packet.AntennaRefreshRequestPayload.TYPE,
            com.florentdubut.telecom.network.packet.AntennaRefreshRequestPayload.STREAM_CODEC,
            ModNetworking::handleAntennaRefreshRequest
        );
    }

    public static void scanForPlayer(ServerPlayer player) {
        if (!acceptRequest(player, RequestCategory.SCAN, 250)) return;
        NetworkScanResponsePayload scan = scanNetworkForPlayer(player);
        if (scan.found()) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
                if (!stack.is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get())) continue;
                net.minecraft.world.item.component.CustomData data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                if (data == null || !data.copyTag().getBooleanOr("nperfActive", false)) continue;
                int techId = scan.tech().startsWith("5G") ? 5
                    : scan.tech().startsWith("4G+") ? 4
                    : scan.tech().startsWith("4G") ? 3
                    : scan.tech().startsWith("3G") ? 2 : 1;
                int signalLevel = scan.signalStrength() > -60 ? 4 : scan.signalStrength() > -80 ? 3
                    : scan.signalStrength() > -100 ? 2 : 1;
                TelecomNetworkGraph.get(player.level()).addCoverageRecord(player.blockPosition(), techId, signalLevel);
                break;
            }
        }
        PacketDistributor.sendToPlayer(player, scan);
    }

    // Shared authoritative calculation; only a first-time mobile address lease is persisted.
    private static NetworkScanResponsePayload scanNetworkForPlayer(ServerPlayer player) {
        ServerLevel level = player.level();
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);

        // Discover candidates first; aggregate only on the selected serving antenna.
        record FreqHit(BlockPos position, String name, TelecomFrequency freq, float signal) {}
        java.util.List<FreqHit> hits = new java.util.ArrayList<>();
        boolean incompleteTerrain = false;
        BlockPos receiver = player.blockPosition().above();

        for (NetworkNode node : graph.getNodes()) {
            if (node.getType() != NetworkNode.NodeType.ANTENNA) continue;
            if (node.getFrequenciesMask() == 0 || node.getPosition().distSqr(receiver) > (double) SignalPropagator.MAX_RANGE * SignalPropagator.MAX_RANGE) continue;
            BlockPos position = node.getPosition();
            AntennaBlockEntity antenna = null;
            if (isLoaded(level, position)) {
                BlockEntity be = level.getBlockEntity(position);
                if (!(be instanceof AntennaBlockEntity loaded)) continue;
                antenna = loaded;
            }
            String name = antenna == null ? "Antenna (" + position.getX() + ", " + position.getY() + ", " + position.getZ() + ")"
                    : antenna.getAntennaName();

            java.util.List<TelecomFrequency> frequencies = new java.util.ArrayList<>();
            for (TelecomFrequency freq : TelecomFrequency.values()) {
                if (antenna != null ? antenna.isFrequencyEnabled(freq) : (node.getFrequenciesMask() & (1 << freq.ordinal())) != 0) frequencies.add(freq);
            }
            if (frequencies.isEmpty()) continue;
            var trace = new SignalPropagator.MultiTrace(position, receiver, frequencies);
            while (!trace.advance(level, 256, Long.MAX_VALUE)) { }
            for (SignalPropagator.SignalResult result : trace.results()) {
                incompleteTerrain |= !result.known;
                float signal = result.powerDbm;
                if (result.known && signal > SignalPropagator.MIN_SIGNAL) {
                    hits.add(new FreqHit(position, name, result.frequency, signal));
                }
            }
        }

        // An unknown alternative must not discard an independently verified radio link.
        if (hits.isEmpty()) {
            return new NetworkScanResponsePayload(false, incompleteTerrain ? "Terrain unavailable" : "No Service", -120, "", "", BlockPos.ZERO, 0, 0, 0);
        }

        FreqHit serving = hits.stream().max((left, right) -> RadioSelection.compare(left.freq(), left.signal(), left.position(),
                right.freq(), right.signal(), right.position())).orElseThrow();
        final String activeTech = serving.freq().getTechnology();

        // Filter to only the best tech (phones aggregate within one tech family at a time)
        java.util.List<FreqHit> activeHits = hits.stream()
            .filter(h -> h.freq().getTechnology().equals(activeTech))
            .toList();

        // Aggregate: sum up max speeds per frequency, weighted by signal quality per hit
        int totalMaxDown = 0;
        int totalMaxUp = 0;

        // Best signal across all active hits (for display)
        float bestSignal = serving.signal();

        // Best antenna (the one with the best signal, used as "source" for routing)
        BlockPos primaryAntenna = serving.position();

        // Multi-site aggregation requires independent routed resources, not free extra capacity.
        activeHits = activeHits.stream().filter(hit -> hit.position().equals(primaryAntenna)).toList();

        // Build label listing all frequencies used
        java.util.List<String> bandLabels = new java.util.ArrayList<>();
        for (FreqHit hit : activeHits) {
            // Signal quality per frequency (affects its contribution)
            float signalQuality = Math.max(0.01f, Math.min(1.0f, (hit.signal() + 120) / 70.0f));
            int freqDown = (int)(hit.freq().getMaxSpeedMb() * signalQuality);
            int freqUp;
            switch (activeTech) {
                case "5G"  -> freqUp = (int)(hit.freq().getMaxSpeedMb() * 0.50f * signalQuality);
                case "4G"  -> freqUp = (int)(hit.freq().getMaxSpeedMb() * 0.30f * signalQuality);
                case "3G"  -> freqUp = (int)(hit.freq().getMaxSpeedMb() * 0.15f * signalQuality);
                default    -> freqUp = (int)(hit.freq().getMaxSpeedMb() * 0.05f * signalQuality);
            }
            totalMaxDown += freqDown;
            totalMaxUp   += Math.max(1, freqUp);
            if (!bandLabels.contains(hit.freq().getFrequencyLabel())) {
                bandLabels.add(hit.freq().getFrequencyLabel());
            }
        }

        String techLabel = activeTech + (activeHits.size() > 1 ? "+" : "")
            + " (" + String.join(", ", bandLabels) + ")";

        String mobileIp = graph.getMobileIp(player.getUUID());
        int frequenciesMask = 0;

        for (FreqHit hit : activeHits) {
            frequenciesMask |= (1 << hit.freq().ordinal());
        }

        return new NetworkScanResponsePayload(
            true,
            serving.name(),
            (int) bestSignal,
            techLabel,
            mobileIp,
            primaryAntenna,
            totalMaxDown,
            Math.max(1, totalMaxUp),
            frequenciesMask
        );
    }

    private static void handleNetworkScanResponse(final NetworkScanResponsePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            com.florentdubut.telecom.client.gui.SmartphoneHUD.latestScan = payload;
            com.florentdubut.telecom.client.gui.SmartphoneHUD.lastScanTime = System.currentTimeMillis();
        });
    }

    private static void handleRouterGuiSync(final com.florentdubut.telecom.network.packet.RouterGuiSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.gui.screens.Screen current = net.minecraft.client.Minecraft.getInstance().screen;
            if (current instanceof com.florentdubut.telecom.client.gui.RouterScreen rs) {
                rs.updatePayload(payload);
            } else if (current instanceof com.florentdubut.telecom.client.gui.SpeedtestServerSelectionScreen selection) {
                selection.updateRouter(payload);
            } else {
                net.minecraft.client.Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.RouterScreen(payload));
            }
        });
    }

    private static void handleServerGuiSync(final com.florentdubut.telecom.network.packet.ServerGuiSyncPayload payload, final IPayloadContext context) {
        net.minecraft.network.Connection connection = context.connection();
        var receivedLevel = net.minecraft.client.Minecraft.getInstance().level;
        context.enqueueWork(() -> {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.getConnection() == null || minecraft.getConnection().getConnection() != connection
                    || minecraft.level == null || minecraft.level != receivedLevel
                    || !minecraft.level.dimension().identifier().toString().equals(payload.dimension())) return;
            if (payload.open()) {
                if (payload.valid()) minecraft.setScreen(new com.florentdubut.telecom.client.gui.ServerScreen(payload));
            } else if (minecraft.screen instanceof com.florentdubut.telecom.client.gui.ServerScreen screen
                    && screen.matchesView(connection, payload)) {
                if (payload.valid()) screen.updatePayload(payload);
                else minecraft.setScreen(null);
            }
        });
    }

    public static ServerGuiSyncPayload createServerSnapshot(ServerPlayer player, BlockPos pos, java.util.UUID viewId, boolean open) {
        ServerLevel level = player.level();
        String dimension = level.dimension().identifier().toString();
        if (!isLoaded(level, pos) || !(level.getBlockEntity(pos) instanceof com.florentdubut.telecom.block.entity.ServerBlockEntity)) {
            return ServerGuiSyncPayload.unavailable(viewId, dimension, pos, open);
        }
        int phones = 0;
        for (ServerPlayer online : level.getServer().getPlayerList().getPlayers()) {
            if (hasSmartphone(online)) phones++;
        }
        return createServerSnapshot(TelecomNetworkGraph.get(level), pos, viewId, dimension, open, phones);
    }

    public static ServerGuiSyncPayload createServerSnapshot(
            TelecomNetworkGraph graph, BlockPos pos, java.util.UUID viewId, String dimension, boolean open, int phones) {
        NetworkNode node = graph.getNode(pos);
        if (node == null || node.getType() != NetworkNode.NodeType.SERVER) return ServerGuiSyncPayload.unavailable(viewId, dimension, pos, open);
        var nodes = new java.util.HashMap<BlockPos, NetworkNode>();
        for (NetworkNode candidate : graph.getNodes()) nodes.put(candidate.getPosition(), candidate);
        var adjacency = new java.util.HashMap<BlockPos, java.util.List<BlockPos>>();
        for (NetworkEdge edge : graph.getEdges()) {
            if (!nodes.containsKey(edge.getNodeA()) || !nodes.containsKey(edge.getNodeB())) continue;
            adjacency.computeIfAbsent(edge.getNodeA(), ignored -> new java.util.ArrayList<>()).add(edge.getNodeB());
            adjacency.computeIfAbsent(edge.getNodeB(), ignored -> new java.util.ArrayList<>()).add(edge.getNodeA());
        }
        // One topological traversal, not one physical path calculation per device.
        var visited = new java.util.HashSet<BlockPos>();
        var queue = new java.util.ArrayDeque<BlockPos>();
        visited.add(pos);
        queue.add(pos);
        int routers = 0;
        int antennas = 0;
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            NetworkNode candidate = nodes.get(current);
            if (candidate != null) {
                if (candidate.getType() == NetworkNode.NodeType.ROUTER) routers++;
                if (candidate.getType() == NetworkNode.NodeType.ANTENNA) antennas++;
            }
            for (BlockPos neighbor : adjacency.getOrDefault(current, java.util.List.of())) {
                if (visited.add(neighbor)) queue.addLast(neighbor);
            }
        }
        return new ServerGuiSyncPayload(viewId, dimension, open, true, pos, routers, antennas, phones,
                node.getCurrentUsageDown(), node.getCurrentUsageUp(), node.getCapacityDown(), node.getCapacityUp());
    }

    private static void handleServerRefreshRequest(RequestServerRefreshPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !acceptRequest(player, RequestCategory.SERVER_REFRESH, 250)) return;
            ServerLevel level = player.level();
            if (!level.dimension().identifier().toString().equals(payload.dimension())
                    || !player.isWithinBlockInteractionRange(payload.pos(), 0)) {
                context.reply(ServerGuiSyncPayload.unavailable(payload.viewId(), payload.dimension(), payload.pos(), false));
                return;
            }
            context.reply(createServerSnapshot(player, payload.pos(), payload.viewId(), false));
        });
    }

    private static void handleGuiRefreshRequest(com.florentdubut.telecom.network.packet.GuiRefreshRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && acceptRequest(player, RequestCategory.GUI_REFRESH, 250)
                    && isLoaded(player.level(), payload.pos())
                    && player.isWithinBlockInteractionRange(payload.pos(), 0)) {
                net.minecraft.world.level.block.state.BlockState state = player.level().getBlockState(payload.pos());
                if (state.getBlock() instanceof com.florentdubut.telecom.block.ServerBlock) return;
                net.minecraft.world.phys.BlockHitResult hitResult = new net.minecraft.world.phys.BlockHitResult(
                    net.minecraft.world.phys.Vec3.atCenterOf(payload.pos()), 
                    net.minecraft.core.Direction.UP, 
                    payload.pos(), 
                    false
                );
                // Trigger the block's useWithoutItem which sends the GUI sync packet back
                if (state.getBlock() instanceof com.florentdubut.telecom.block.TelecomBlock) {
                    state.useWithoutItem(player.level(), player, hitResult);
                    if (state.getBlock() instanceof com.florentdubut.telecom.block.RouterBlock) {
                        TrafficSession latest = TelecomNetworkGraph.get(player.level()).getLatestSessionByDeviceId(TrafficSession.routerDeviceId(payload.pos()));
                        if (latest != null) sendSpeedtestState(player, latest, latest.getDeviceId(), latest.getClientIp());
                    }
                }
            }
        });
    }

    private static void handleNetworkToolRefreshRequest(com.florentdubut.telecom.network.packet.NetworkToolRefreshRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && acceptRequest(player, RequestCategory.TOOL_REFRESH, 250)
                    && isLoaded(player.level(), payload.clickedPos())) {
                var snapshot = createNetworkToolSnapshot(TelecomNetworkGraph.get(player.level()), payload.clickedPos());
                if (snapshot != null) PacketDistributor.sendToPlayer(player, snapshot);
            }
        });
    }

    public static com.florentdubut.telecom.network.packet.NetworkToolSyncPayload createNetworkToolSnapshot(
            TelecomNetworkGraph graph, BlockPos pos) {
        NetworkNode node = graph.getNode(pos);
        if (node != null) {
            return new com.florentdubut.telecom.network.packet.NetworkToolSyncPayload(pos,
                    "gui.telecom.tool.node." + node.getType().name().toLowerCase(java.util.Locale.ROOT), 0,
                    node.getCapacityDown(), node.getCapacityUp(), node.getCurrentUsageDown(), node.getCurrentUsageUp(),
                    com.florentdubut.telecom.network.packet.NetworkToolSyncPayload.CapacityMode.DIRECTIONAL);
        }
        // Metadata describes the limiting link; capacity and usage belong to the physical cable block.
        NetworkEdge edge = graph.getEdges().stream()
                .filter(candidate -> candidate.getPathBlocks() != null && candidate.getPathBlocks().contains(pos))
                .min(java.util.Comparator.comparingInt(NetworkEdge::getEffectiveBandwidthMbps)
                        .thenComparingInt(NetworkEdge::getLength).thenComparing(candidate -> candidate.getType().name()))
                .orElse(null);
        if (edge == null) return null;
        int capacity = graph.getActualBlockCapacityMbps(pos);
        return new com.florentdubut.telecom.network.packet.NetworkToolSyncPayload(pos,
                "gui.telecom.tool.edge." + edge.getType().name().toLowerCase(java.util.Locale.ROOT), edge.getLength(),
                capacity, capacity, graph.getActualBlockUsageDown(pos), graph.getActualBlockUsageUp(pos),
                com.florentdubut.telecom.network.packet.NetworkToolSyncPayload.CapacityMode.SHARED);
    }

    private static void handleNetworkToolSync(com.florentdubut.telecom.network.packet.NetworkToolSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen instanceof com.florentdubut.telecom.client.gui.NetworkToolScreen screen) {
                screen.updatePayload(payload);
            } else {
                net.minecraft.client.Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.NetworkToolScreen(payload));
            }
        });
    }

    /**
     * Called server-side when a player right-clicks an antenna.
     * Gathers utilization data and sends AntennaGuiSyncPayload to that player.
     */
    public static void openAntennaGuiForPlayer(ServerPlayer player, AntennaBlockEntity antenna) {
        ServerLevel level = player.level();
        if (antenna.getLevel() != level || !isLoaded(level, antenna.getBlockPos())) return;
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);

        java.util.Map<Integer, int[]> utilMap = new java.util.HashMap<>();

        java.util.Map<TelecomFrequency, TelecomNetworkGraph.AntennaFreqStats> utilization =
            graph.getAntennaUtilization(antenna.getBlockPos());

        // Include enabled frequencies with their live utilization (or 0 if no session)
        for (TelecomFrequency freq : TelecomFrequency.values()) {
            if (antenna.isFrequencyEnabled(freq)) {
                TelecomNetworkGraph.AntennaFreqStats stats = utilization.get(freq);
                int actual = stats != null ? stats.actualMbps() : 0;
                int max = freq.getMaxSpeedMb();
                utilMap.put(freq.ordinal(), new int[]{actual, max});
            }
        }

        PacketDistributor.sendToPlayer(player, new com.florentdubut.telecom.network.packet.AntennaGuiSyncPayload(
            antenna.getBlockPos(),
            antenna.getAntennaName(),
            antenna.getEnabledFrequenciesMask(),
            utilMap
        ));
    }

    private static void handleAntennaGuiSync(final com.florentdubut.telecom.network.packet.AntennaGuiSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.gui.screens.Screen current = net.minecraft.client.Minecraft.getInstance().screen;
            if (current instanceof com.florentdubut.telecom.client.gui.AntennaScreen existing) {
                // Refresh the live data without reopening the screen
                existing.receiveUpdate(payload);
            } else {
                net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.florentdubut.telecom.client.gui.AntennaScreen(payload)
                );
            }
        });
    }

    private static void handleAntennaConfig(final AntennaConfigPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !acceptRequest(player, RequestCategory.ANTENNA_CONFIG, 100)
                    || player.isSpectator()) return;
            ServerLevel level = player.level();
            BlockPos pos = payload.pos();
            if (!isLoaded(level, pos) || !player.isWithinBlockInteractionRange(pos, 0) || !level.mayInteract(player, pos)
                    || payload.name().length() > 32 || payload.name().codePoints().anyMatch(Character::isISOControl)
                    || (payload.enabledFrequenciesMask() & ~VALID_FREQUENCIES_MASK) != 0) return;
            if (level.getBlockEntity(pos) instanceof AntennaBlockEntity antenna) {
                if (!antenna.getAntennaName().equals(payload.name())) antenna.setAntennaName(payload.name());
                if (antenna.getEnabledFrequenciesMask() != payload.enabledFrequenciesMask()) {
                    antenna.setEnabledFrequenciesMask(payload.enabledFrequenciesMask());
                }
            }
        });
    }

    private static void handleRouterConfig(final com.florentdubut.telecom.network.packet.RouterConfigPayload payload, final IPayloadContext context) {
        // Configuration is hardcoded by router tier. No work needs to be enqueued.
    }

    private static void handleAntennaRefreshRequest(
            final com.florentdubut.telecom.network.packet.AntennaRefreshRequestPayload payload,
            final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer
                    && acceptRequest(serverPlayer, RequestCategory.ANTENNA_REFRESH, 250)) {
                ServerLevel level = serverPlayer.level();
                if (!isLoaded(level, payload.pos())) return;
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(payload.pos());
                if (be instanceof AntennaBlockEntity antenna) {
                    openAntennaGuiForPlayer(serverPlayer, antenna);
                }
            }
        });
    }

    private static void handleRequestSpeedtestServers(RequestSpeedtestServersPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !acceptRequest(player, RequestCategory.SPEEDTEST_SERVERS, 1000)) return;
            ServerLevel level = player.level();
            if (!level.dimension().identifier().toString().equals(payload.dimension())) return;
            String deviceId = payload.mobile() ? TrafficSession.mobileDeviceId(player.getUUID())
                    : TrafficSession.routerDeviceId(payload.sourcePos());
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
            BlockPos sourcePos = payload.sourcePos();
            int extraPing = 0;
            String error = "";
            if (player.isSpectator()) {
                error = "invalid_request";
            } else if (payload.mobile()) {
                if (!hasSmartphone(player)) {
                    error = "invalid_request";
                } else {
                    NetworkScanResponsePayload scan = scanNetworkForPlayer(player);
                    if (!scan.found()) error = "no_server";
                    sourcePos = scan.antennaPos();
                    extraPing = scan.tech().startsWith("5G") ? 15 : scan.tech().startsWith("4G") ? 40
                            : scan.tech().startsWith("3G") ? 95 : 300;
                }
            } else if (!isLoaded(level, sourcePos) || !player.isWithinBlockInteractionRange(sourcePos, 0)
                    || !(level.getBlockEntity(sourcePos) instanceof RouterBlockEntity)
                    || graph.getNode(sourcePos) == null || graph.getNode(sourcePos).getType() != NetworkNode.NodeType.ROUTER) {
                error = "invalid_request";
            }
            java.util.List<SpeedtestServerOption> servers = java.util.List.of();
            if (error.isEmpty()) {
                try {
                    servers = graph.getSpeedtestServers(sourcePos, extraPing);
                } catch (TelecomNetworkGraph.SpeedtestCatalogueLimitException exception) {
                    error = "catalogue_limit";
                }
            }
            boolean truncated = error.isEmpty() && graph.getNodes().stream()
                    .filter(node -> node.getType() == NetworkNode.NodeType.SERVER)
                    .limit(TelecomNetworkGraph.MAX_SPEEDTEST_SERVERS + 1L).count() > TelecomNetworkGraph.MAX_SPEEDTEST_SERVERS;
            context.reply(new SpeedtestServersPayload(payload.requestId(), payload.dimension(), deviceId, servers, truncated, error));
        });
    }

    private static void handleSpeedtestServers(SpeedtestServersPayload payload, IPayloadContext context) {
        net.minecraft.network.Connection connection = context.connection();
        context.enqueueWork(() -> {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.getConnection() == null || minecraft.getConnection().getConnection() != connection
                    || minecraft.level == null || !minecraft.level.dimension().identifier().toString().equals(payload.dimension())) return;
            if (minecraft.screen instanceof com.florentdubut.telecom.client.gui.SpeedtestServerSelectionScreen screen) {
                screen.receiveServers(connection, payload);
            }
        });
    }

    private static void handleStartSpeedtest(final com.florentdubut.telecom.network.packet.StartSpeedtestPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !acceptRequest(player, RequestCategory.SPEEDTEST, 100)) return;
            // Router requests have no radio bands. Mobile bands identify the context only;
            // the actual antenna, address, bandwidth and bands are always rescanned server-side.
            boolean mobile = payload.frequenciesMask() != 0;
            String deviceId = mobile ? TrafficSession.mobileDeviceId(player.getUUID()) : TrafficSession.routerDeviceId(payload.sourcePos());
            ServerLevel level = player.level();
            if (!level.dimension().identifier().toString().equals(payload.dimension())) {
                // A delayed request belongs to its original dimension, never the new world's device at the same position.
                context.reply(new com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload(
                        "", "REJECTED", 0, 0, 0, 0, payload.dimension(), deviceId, new java.util.UUID(0, 0),
                        0, 0, payload.serverId(), "", "invalid_request"));
                return;
            }
            if (player.isSpectator() || (payload.frequenciesMask() & ~VALID_FREQUENCIES_MASK) != 0) {
                sendSpeedtestState(player, null, deviceId, "", payload.serverId(), "invalid_request");
                return;
            }
            if (payload.durationTicks() != 300 && payload.durationTicks() != 600 && payload.durationTicks() != 1200
                    && payload.durationTicks() != 6000 && payload.durationTicks() != 12000) {
                sendSpeedtestState(player, null, deviceId, "", payload.serverId(), "invalid_request");
                return;
            }

            TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
            NetworkNode source = graph.getNode(payload.sourcePos());
            if (!mobile) {
                if (source == null || source.getType() != NetworkNode.NodeType.ROUTER
                        || !isLoaded(level, payload.sourcePos()) || !player.isWithinBlockInteractionRange(payload.sourcePos(), 0)
                        || source.getIpAddress() == null || source.getIpAddress().isBlank()
                        || !(level.getBlockEntity(payload.sourcePos()) instanceof RouterBlockEntity router)) {
                    sendSpeedtestState(player, null, deviceId, "", payload.serverId(), "invalid_request");
                    return;
                }
                int maxDown = source.getCapacityDown();
                int maxUp = source.getCapacityUp();
                var result = graph.startSpeedtest(source.getPosition(), source.getIpAddress(), maxDown, maxUp, 0, 0,
                    payload.durationTicks(), false, player, payload.serverId());
                sendSpeedtestState(player, result.session(), deviceId, source.getIpAddress(), payload.serverId(), result.error());
                return;
            }

            if (!hasSmartphone(player)) {
                sendSpeedtestState(player, null, deviceId, "", payload.serverId(), "invalid_request");
                return;
            }
            NetworkScanResponsePayload scan = scanNetworkForPlayer(player);
            if (!scan.found() || scan.maxDown() <= 0 || scan.maxUp() <= 0) {
                sendSpeedtestState(player, null, deviceId, "", payload.serverId(), payload.serverId().isEmpty() ? "no_server" : "server_unavailable");
                return;
            }
            int extraPing = scan.tech().startsWith("5G") ? 10 + level.random.nextInt(10)
                : scan.tech().startsWith("4G") ? 30 + level.random.nextInt(20)
                : scan.tech().startsWith("3G") ? 70 + level.random.nextInt(50)
                : 200 + level.random.nextInt(200);
            var result = graph.startSpeedtest(scan.antennaPos(), scan.ipAddress(), scan.maxDown(), scan.maxUp(), extraPing,
                scan.frequenciesMask(), payload.durationTicks(), false, player, payload.serverId());
            sendSpeedtestState(player, result.session(), deviceId, scan.ipAddress(), payload.serverId(), result.error());
        });
    }

    private static void sendSpeedtestState(ServerPlayer player, TrafficSession session, String deviceId, String ip) {
        sendSpeedtestState(player, session, deviceId, ip, "", "");
    }

    private static void sendSpeedtestState(ServerPlayer player, TrafficSession session, String deviceId, String ip, String requestedServer, String error) {
        boolean rejected = session == null || session.isPassive();
        PacketDistributor.sendToPlayer(player, new com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload(
                ip == null ? "" : ip, rejected ? "REJECTED" : session.getState().name(),
                rejected ? 0 : session.getPingMs(), rejected ? 0 : session.getMeasuredBandwidth(),
                rejected ? 0 : session.getTicksElapsed(), rejected ? 0 : session.getTotalTicksPerPhase(),
                player.level().dimension().identifier().toString(), deviceId,
                rejected ? new java.util.UUID(0, 0) : session.getSessionId(),
                rejected ? 0 : session.getFinalDownBw(), rejected ? 0 : session.getFinalUpBw(),
                rejected ? requestedServer : session.getServerId(), rejected ? "" : session.getServerName(),
                rejected ? error : session.getFailureReason()));
    }

    private static void handleSpeedtestUpdate(final com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload payload, final IPayloadContext context) {
        net.minecraft.network.Connection sourceConnection = context.connection();
        context.enqueueWork(() -> {
            if (!com.florentdubut.telecom.client.ClientSpeedtestState.accept(sourceConnection, payload)) return;
            net.minecraft.client.gui.screens.Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
            if (screen instanceof com.florentdubut.telecom.client.gui.RouterScreen routerScreen) {
                routerScreen.updateSpeedtestProgress(payload);
            } else if (screen instanceof com.florentdubut.telecom.client.gui.SmartphoneSpeedtestScreen phoneScreen) {
                phoneScreen.updateSpeedtestProgress(payload);
            } else if (screen instanceof com.florentdubut.telecom.client.gui.SpeedtestServerSelectionScreen selection) {
                selection.updateSpeedtestProgress(payload);
            }
        });
    }

    private static void handleServerBandwidthUpdate(final com.florentdubut.telecom.network.packet.ServerBandwidthUpdatePayload payload, final IPayloadContext context) {
        // Global traffic is not the utilization of the server being inspected.
        // ServerScreen refreshes its own node snapshot instead.
    }

    private static void handleRequestCoverageTile(RequestCoverageTilePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !acceptRequest(player, RequestCategory.COVERAGE, 100)) return;
            ServerLevel level = player.level();
            if (!level.dimension().identifier().toString().equals(payload.dimension())) return;
            try {
                CoverageService.Request request = payload.request();
                String snapshot = CoverageService.request(level, request,
                        System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2));
                context.reply(CoverageTilePayload.fromSnapshot(payload, snapshot));
            } catch (IllegalArgumentException exception) {
                context.reply(CoverageTilePayload.unavailable(payload, "invalid"));
            } catch (CoverageService.BusyException exception) {
                context.reply(CoverageTilePayload.unavailable(payload, exception.retryable() ? "busy" : "limited"));
            }
        });
    }

    private static void handleCoverageTile(CoverageTilePayload payload, IPayloadContext context) {
        net.minecraft.network.Connection connection = context.connection();
        context.enqueueWork(() -> {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.getConnection() == null || minecraft.getConnection().getConnection() != connection
                    || minecraft.level == null
                    || !minecraft.level.dimension().identifier().toString().equals(payload.dimension())) return;
            if (minecraft.screen instanceof com.florentdubut.telecom.client.gui.NetworkMapScreen screen) {
                screen.receiveCoverage(payload);
            }
        });
    }

    private static void handleRequestNetworkMap(final com.florentdubut.telecom.network.packet.RequestNetworkMapPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !acceptRequest(player, RequestCategory.MAP, 1000)) return;
            ServerLevel level = player.level();
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
            if (graph != null) {
                java.util.List<com.florentdubut.telecom.network.packet.MapNodeData> nodesData = new java.util.ArrayList<>();
                for (com.florentdubut.telecom.network.NetworkNode node : graph.getNodes()) {
                    String extraInfo = "";
                    if (node.getType() == com.florentdubut.telecom.network.NetworkNode.NodeType.ANTENNA
                            && isLoaded(level, node.getPosition())) {
                        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(node.getPosition());
                        if (be instanceof com.florentdubut.telecom.block.entity.AntennaBlockEntity antenna) {
                            java.util.List<String> techs = new java.util.ArrayList<>();
                            for (com.florentdubut.telecom.network.TelecomFrequency freq : com.florentdubut.telecom.network.TelecomFrequency.values()) {
                                if (antenna.isFrequencyEnabled(freq)) {
                                    techs.add(freq.getTechnology());
                                }
                            }
                            // remove duplicates and join
                            extraInfo = String.join(", ", techs.stream().distinct().toList());
                        }
                    }
                    nodesData.add(new com.florentdubut.telecom.network.packet.MapNodeData(
                        node.getPosition(),
                        node.getType().name(),
                        node.getIpAddress() != null ? node.getIpAddress() : "",
                        extraInfo
                    ));
                }
                context.reply(new com.florentdubut.telecom.network.packet.NetworkMapResponsePayload(nodesData));
            }
        });
    }

    private static void handleNetworkMapResponse(final com.florentdubut.telecom.network.packet.NetworkMapResponsePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.gui.screens.Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
            if (screen instanceof com.florentdubut.telecom.client.gui.NetworkMapScreen mapScreen) {
                mapScreen.receiveData(payload.nodes());
            }
        });
    }

    private static void handleToggleNperf(final com.florentdubut.telecom.network.packet.ToggleNperfPayload payload, final net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !acceptRequest(player, RequestCategory.NPERF, 250) || player.isSpectator()) return;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get())) {
                    net.minecraft.nbt.CompoundTag tag = stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA) ? stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag() : new net.minecraft.nbt.CompoundTag();
                    tag.putBoolean("nperfActive", payload.enabled());
                    stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("Nperf " + (payload.enabled() ? "Activated" : "Deactivated")), true);
                    break;
                }
            }
        });
    }
}
