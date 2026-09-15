package com.florentdubut.telecom.network;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.block.entity.RouterBlockEntity;
import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;
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
        ANTENNA_CONFIG, GUI_REFRESH, ANTENNA_REFRESH, TOOL_REFRESH, MAP, SPEEDTEST, NPERF, SCAN
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
        final PayloadRegistrar registrar = event.registrar("1.0");

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
        record FreqHit(AntennaBlockEntity antenna, TelecomFrequency freq, float signal) {}
        java.util.List<FreqHit> hits = new java.util.ArrayList<>();

        for (NetworkNode node : graph.getNodes()) {
            if (node.getType() != NetworkNode.NodeType.ANTENNA) continue;
            if (node.getIpAddress() == null || node.getIpAddress().isBlank()) continue;
            if (!isLoaded(level, node.getPosition())) continue;

            BlockEntity be = level.getBlockEntity(node.getPosition());
            if (!(be instanceof AntennaBlockEntity antenna)) continue;

            for (TelecomFrequency freq : TelecomFrequency.values()) {
                if (!antenna.isFrequencyEnabled(freq)) continue;
                float signal = com.florentdubut.telecom.network.SignalPropagator.calculateSignal(
                    level, antenna.getBlockPos(), player.blockPosition().above(), freq).powerDbm;
                if (signal > -120f) {
                    hits.add(new FreqHit(antenna, freq, signal));
                }
            }
        }

        if (hits.isEmpty()) {
            return new NetworkScanResponsePayload(false, "No Service", -120, "", "", BlockPos.ZERO, 0, 0, 0);
        }

        // Determine the best technology available (5G > 4G > 3G > 2G)
        String[] techOrder = {"5G", "4G", "3G", "2G"};
        String bestTech = null;
        for (String tech : techOrder) {
            if (hits.stream().anyMatch(h -> h.freq().getTechnology().equals(tech))) {
                bestTech = tech;
                break;
            }
        }
        final String activeTech = bestTech;

        // Filter to only the best tech (phones aggregate within one tech family at a time)
        java.util.List<FreqHit> activeHits = hits.stream()
            .filter(h -> h.freq().getTechnology().equals(activeTech))
            .toList();

        // Aggregate: sum up max speeds per frequency, weighted by signal quality per hit
        int totalMaxDown = 0;
        int totalMaxUp = 0;

        // Best signal across all active hits (for display)
        float bestSignal = activeHits.stream().map(FreqHit::signal).max(Float::compareTo).orElse(-120f);

        // Best antenna (the one with the best signal, used as "source" for routing)
        AntennaBlockEntity primaryAntenna = activeHits.stream()
            .max((a, b) -> Float.compare(a.signal(), b.signal()))
            .map(FreqHit::antenna)
            .orElse(null);

        // Multi-site aggregation requires independent routed resources, not free extra capacity.
        activeHits = activeHits.stream().filter(hit -> hit.antenna() == primaryAntenna).toList();

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
            primaryAntenna != null ? primaryAntenna.getAntennaName() : "Unknown",
            (int) bestSignal,
            techLabel,
            mobileIp,
            primaryAntenna != null ? primaryAntenna.getBlockPos() : BlockPos.ZERO,
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
            } else {
                net.minecraft.client.Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.RouterScreen(payload));
            }
        });
    }

    private static void handleServerGuiSync(final com.florentdubut.telecom.network.packet.ServerGuiSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.gui.screens.Screen current = net.minecraft.client.Minecraft.getInstance().screen;
            if (current instanceof com.florentdubut.telecom.client.gui.ServerScreen ss) {
                ss.updatePayload(payload);
            } else {
                net.minecraft.client.Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.ServerScreen(payload));
            }
        });
    }

    private static void handleGuiRefreshRequest(com.florentdubut.telecom.network.packet.GuiRefreshRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && acceptRequest(player, RequestCategory.GUI_REFRESH, 250)
                    && isLoaded(player.level(), payload.pos())) {
                net.minecraft.world.level.block.state.BlockState state = player.level().getBlockState(payload.pos());
                net.minecraft.world.phys.BlockHitResult hitResult = new net.minecraft.world.phys.BlockHitResult(
                    net.minecraft.world.phys.Vec3.atCenterOf(payload.pos()), 
                    net.minecraft.core.Direction.UP, 
                    payload.pos(), 
                    false
                );
                // Trigger the block's useWithoutItem which sends the GUI sync packet back
                if (state.getBlock() instanceof com.florentdubut.telecom.block.TelecomBlock) {
                    state.useWithoutItem(player.level(), player, hitResult);
                }
            }
        });
    }

    private static void handleNetworkToolRefreshRequest(com.florentdubut.telecom.network.packet.NetworkToolRefreshRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && acceptRequest(player, RequestCategory.TOOL_REFRESH, 250)
                    && isLoaded(player.level(), payload.clickedPos())) {
                com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get(player.level());
                net.minecraft.core.BlockPos clickedPos = payload.clickedPos();
                
                // First check if it's a node
                com.florentdubut.telecom.network.NetworkNode clickedNode = graph.getNode(clickedPos);
                if (clickedNode != null) {
                    int usageDown = clickedNode.getCurrentUsageDown();
                    int usageUp = clickedNode.getCurrentUsageUp();
                    int maxBandwidth = 0;
                    for (com.florentdubut.telecom.network.NetworkEdge edge : graph.getEdges()) {
                        if (edge.getNodeA().equals(clickedPos) || edge.getNodeB().equals(clickedPos)) {
                            maxBandwidth = Math.max(maxBandwidth, edge.getBandwidthMax());
                        }
                    }
                    String typeStr = "Network Node (" + clickedNode.getType().name() + ")";
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player,
                        new com.florentdubut.telecom.network.packet.NetworkToolSyncPayload(clickedPos, typeStr, 0, maxBandwidth == 0 ? 1000 : maxBandwidth, usageDown, usageUp)
                    );
                    return;
                }
                
                // Find the edge containing this block
                for (com.florentdubut.telecom.network.NetworkEdge edge : graph.getEdges()) {
                    if (edge.getPathBlocks() != null && edge.getPathBlocks().contains(clickedPos)) {
                        String typeStr = "Unknown";
                        if (edge.getType() == com.florentdubut.telecom.network.NetworkEdge.EdgeType.BIG_FIBER) typeStr = "Big Fiber Optic";
                        else if (edge.getType() == com.florentdubut.telecom.network.NetworkEdge.EdgeType.MEDIUM_FIBER) typeStr = "Medium Fiber Optic";
                        else if (edge.getType() == com.florentdubut.telecom.network.NetworkEdge.EdgeType.FIBER) typeStr = "Fiber Optic";
                        else if (edge.getType() == com.florentdubut.telecom.network.NetworkEdge.EdgeType.COPPER) typeStr = "Copper ADSL";
                        
                        int usageDown = graph.getActualBlockUsageDown(clickedPos);
                        int usageUp = graph.getActualBlockUsageUp(clickedPos);
                        
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                            player,
                            new com.florentdubut.telecom.network.packet.NetworkToolSyncPayload(clickedPos, typeStr, edge.getLength(), edge.getBandwidthMax(), usageDown, usageUp)
                        );
                        return;
                    }
                }
            }
        });
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

    private static void handleStartSpeedtest(final com.florentdubut.telecom.network.packet.StartSpeedtestPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !acceptRequest(player, RequestCategory.SPEEDTEST, 1000)
                    || player.isSpectator()) return;
            if (payload.durationTicks() != 300 && payload.durationTicks() != 600 && payload.durationTicks() != 1200
                    && payload.durationTicks() != 6000 && payload.durationTicks() != 12000) return;

            ServerLevel level = player.level();
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
            NetworkNode source = graph.getNode(payload.sourcePos());
            if (source != null && source.getType() == NetworkNode.NodeType.ROUTER) {
                if (!isLoaded(level, payload.sourcePos()) || !player.isWithinBlockInteractionRange(payload.sourcePos(), 0)
                        || source.getIpAddress() == null || source.getIpAddress().isBlank()
                        || !(level.getBlockEntity(payload.sourcePos()) instanceof RouterBlockEntity router)) return;
                int maxDown = router.getConfiguredMaxDown();
                int maxUp = router.getConfiguredMaxUp();
                if (maxDown <= 0 || maxUp <= 0) return;
                graph.startSpeedtest(source.getPosition(), source.getIpAddress(), maxDown, maxUp, 0, 0,
                    payload.durationTicks(), false, player);
                return;
            }

            if (!hasSmartphone(player)) return;
            NetworkScanResponsePayload scan = scanNetworkForPlayer(player);
            if (!scan.found() || scan.maxDown() <= 0 || scan.maxUp() <= 0) return;
            int extraPing = scan.tech().startsWith("5G") ? 10 + level.random.nextInt(10)
                : scan.tech().startsWith("4G") ? 30 + level.random.nextInt(20)
                : scan.tech().startsWith("3G") ? 70 + level.random.nextInt(50)
                : 200 + level.random.nextInt(200);
            graph.startSpeedtest(scan.antennaPos(), scan.ipAddress(), scan.maxDown(), scan.maxUp(), extraPing,
                scan.frequenciesMask(), payload.durationTicks(), false, player);
        });
    }

    private static void handleSpeedtestUpdate(final com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.gui.screens.Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
            if (screen instanceof com.florentdubut.telecom.client.gui.RouterScreen routerScreen) {
                routerScreen.updateSpeedtestProgress(payload);
            } else if (screen instanceof com.florentdubut.telecom.client.gui.SmartphoneSpeedtestScreen phoneScreen) {
                phoneScreen.updateSpeedtestProgress(payload);
            }
        });
    }

    private static void handleServerBandwidthUpdate(final com.florentdubut.telecom.network.packet.ServerBandwidthUpdatePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.gui.screens.Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
            if (screen instanceof com.florentdubut.telecom.client.gui.ServerScreen serverScreen) {
                serverScreen.updateBandwidth(payload.totalBandwidthDown(), payload.totalBandwidthUp());
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
