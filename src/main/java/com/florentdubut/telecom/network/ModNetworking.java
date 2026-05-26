package com.florentdubut.telecom.network;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomFrequency;
import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = TelecomMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModNetworking {

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1.0");

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
        ServerLevel level = player.serverLevel();
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);

        // Carrier Aggregation: collect ALL valid (antenna, frequency) pairs reachable by the player
        // Each pair has a signal quality and a contribution to max down/up bandwidth.
        record FreqHit(AntennaBlockEntity antenna, TelecomFrequency freq, float signal) {}
        java.util.List<FreqHit> hits = new java.util.ArrayList<>();

        for (NetworkNode node : graph.getNodes()) {
            if (node.getType() != NetworkNode.NodeType.ANTENNA) continue;
            if (node.getIpAddress() == null) continue; // Not connected to network

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
            PacketDistributor.sendToPlayer(player, new NetworkScanResponsePayload(false, "No Service", -120, "", "", BlockPos.ZERO, 0, 0));
            return;
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
        TelecomFrequency primaryFreq = activeHits.stream()
            .max((a, b) -> Float.compare(a.signal(), b.signal()))
            .map(FreqHit::freq)
            .orElse(null);

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

        // Mobile IP derived from primary antenna + player ID
        String mobileIp = primaryAntenna != null
            ? "10.0." + (primaryAntenna.getBlockPos().getX() % 255) + "." + (player.getId() % 255)
            : "0.0.0.0";

        PacketDistributor.sendToPlayer(player, new NetworkScanResponsePayload(
            true,
            primaryAntenna != null ? primaryAntenna.getAntennaName() : "Unknown",
            (int) bestSignal,
            techLabel,
            mobileIp,
            primaryAntenna != null ? primaryAntenna.getBlockPos() : BlockPos.ZERO,
            totalMaxDown,
            Math.max(1, totalMaxUp)
        ));
    }

    private static void handleNetworkScanResponse(final NetworkScanResponsePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            com.florentdubut.telecom.client.gui.SmartphoneHUD.latestScan = payload;
            com.florentdubut.telecom.client.gui.SmartphoneHUD.lastScanTime = System.currentTimeMillis();
        });
    }

    private static void handleRouterGuiSync(final com.florentdubut.telecom.network.packet.RouterGuiSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.RouterScreen(payload));
        });
    }

    private static void handleServerGuiSync(final com.florentdubut.telecom.network.packet.ServerGuiSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.ServerScreen(payload));
        });
    }

    private static void handleNetworkToolRefreshRequest(com.florentdubut.telecom.network.packet.NetworkToolRefreshRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) context.player();
            if (player != null) {
                com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get(player.serverLevel());
                net.minecraft.core.BlockPos clickedPos = payload.clickedPos();
                
                // Find the edge containing this block
                for (com.florentdubut.telecom.network.NetworkEdge edge : graph.getEdges()) {
                    if (edge.getPathBlocks() != null && edge.getPathBlocks().contains(clickedPos)) {
                        String typeStr = edge.getType() == com.florentdubut.telecom.network.NetworkEdge.EdgeType.FIBER ? "Fiber Optic" : "Copper ADSL";
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
        ServerLevel level = player.serverLevel();
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);

        java.util.Map<TelecomNetworkGraph.AntennaFreqStats, Object> raw = new java.util.LinkedHashMap<>();
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
            Level level = context.player().level();
            BlockPos pos = payload.pos();
            if (level.isLoaded(pos)) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof AntennaBlockEntity antenna) {
                    antenna.setAntennaName(payload.name());
                    antenna.setEnabledFrequenciesMask(payload.enabledFrequenciesMask());
                }
            }
        });
    }

    private static void handleRouterConfig(final com.florentdubut.telecom.network.packet.RouterConfigPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            // Configuration is now hardcoded by the router tier. We ignore this packet.
        });
    }

    private static void handleAntennaRefreshRequest(
            final com.florentdubut.telecom.network.packet.AntennaRefreshRequestPayload payload,
            final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                ServerLevel level = serverPlayer.serverLevel();
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(payload.pos());
                if (be instanceof AntennaBlockEntity antenna) {
                    openAntennaGuiForPlayer(serverPlayer, antenna);
                }
            }
        });
    }

    private static void handleStartSpeedtest(final com.florentdubut.telecom.network.packet.StartSpeedtestPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().level() instanceof ServerLevel serverLevel) {
                com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get(serverLevel);
                graph.startSpeedtest(payload.sourcePos(), payload.clientIp(), payload.targetDownBw(), payload.targetUpBw(), payload.extraPing(), false);
            }
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
            net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) context.player().level();
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
            if (graph != null) {
                java.util.List<com.florentdubut.telecom.network.packet.MapNodeData> nodesData = new java.util.ArrayList<>();
                for (com.florentdubut.telecom.network.NetworkNode node : graph.getNodes()) {
                    String extraInfo = "";
                    if (node.getType() == com.florentdubut.telecom.network.NetworkNode.NodeType.ANTENNA) {
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
}
