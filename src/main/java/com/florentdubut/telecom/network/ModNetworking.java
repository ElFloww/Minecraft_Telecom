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
    }

    public static void scanForPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
        
        AntennaBlockEntity bestAntenna = null;
        float bestSignal = -1000f;
        String finalTechLabel = "";

        for (NetworkNode node : graph.getNodes()) {
            if (node.getType() == NetworkNode.NodeType.ANTENNA) {
                BlockEntity be = level.getBlockEntity(node.getPosition());
                if (be instanceof AntennaBlockEntity antenna) {
                    NetworkNode antennaNode = graph.getNode(antenna.getBlockPos());
                    if (antennaNode == null || antennaNode.getIpAddress() == null) {
                        continue; // Antenna is not connected to a Server!
                    }
                    
                    // Group valid frequencies by tech
                    java.util.Map<String, java.util.List<TelecomFrequency>> validFreqs = new java.util.HashMap<>();
                    java.util.Map<TelecomFrequency, Float> signals = new java.util.HashMap<>();
                    
                    for (TelecomFrequency freq : TelecomFrequency.values()) {
                        if (antenna.isFrequencyEnabled(freq)) {
                            float signal = com.florentdubut.telecom.network.SignalPropagator.calculateSignal(level, antenna.getBlockPos(), player.blockPosition().above(), freq).powerDbm;
                            
                            if (signal > -120f) { // Valid connection
                                validFreqs.computeIfAbsent(freq.getTechnology(), k -> new java.util.ArrayList<>()).add(freq);
                                signals.put(freq, signal);
                            }
                        }
                    }
                    
                    // Determine the best tech for this antenna
                    String[] techOrder = {"5G", "4G", "3G", "2G"};
                    for (String tech : techOrder) {
                        if (validFreqs.containsKey(tech)) {
                            java.util.List<TelecomFrequency> freqs = validFreqs.get(tech);
                            
                            float maxSignal = -1000f;
                            java.util.List<String> bands = new java.util.ArrayList<>();
                            
                            for (TelecomFrequency f : freqs) {
                                if (signals.get(f) > maxSignal) maxSignal = signals.get(f);
                                bands.add(f.getFrequencyLabel());
                            }
                            
                            String techName = tech + (freqs.size() > 1 ? "+" : "");
                            String fullLabel = techName + " (" + String.join(", ", bands) + ")";
                            
                            // If this antenna provides a better signal or better tech than the previous best antenna
                            // We'll score tech: 5G=4, 4G=3, etc.
                            int currentTechScore = 5 - java.util.Arrays.asList(techOrder).indexOf(tech);
                            int bestTechScore = 0;
                            if (finalTechLabel.contains("5G")) bestTechScore = 4;
                            else if (finalTechLabel.contains("4G")) bestTechScore = 3;
                            else if (finalTechLabel.contains("3G")) bestTechScore = 2;
                            else if (finalTechLabel.contains("2G")) bestTechScore = 1;
                            
                            if (currentTechScore > bestTechScore || (currentTechScore == bestTechScore && maxSignal > bestSignal)) {
                                bestSignal = maxSignal;
                                bestAntenna = antenna;
                                finalTechLabel = fullLabel;
                            }
                            break; // Only pick the highest tech for this antenna
                        }
                    }
                }
            }
        }

        if (bestAntenna != null && !finalTechLabel.isEmpty()) {
            // Recompute max capacities for the selected tech and frequencies
            int maxDown = 0;
            int maxUp = 0;
            
            String selectedTech = finalTechLabel.substring(0, 2);
            for (TelecomFrequency freq : TelecomFrequency.values()) {
                if (freq.getTechnology().equals(selectedTech) && finalTechLabel.contains(freq.getFrequencyLabel())) {
                    // Base speed per frequency
                    maxDown += freq.getMaxSpeedMb();
                    
                    // Upload is typically much slower on mobile networks
                    if (selectedTech.equals("5G")) maxUp += freq.getMaxSpeedMb() * 0.15f;
                    else if (selectedTech.equals("4G")) maxUp += freq.getMaxSpeedMb() * 0.25f;
                    else if (selectedTech.equals("3G")) maxUp += freq.getMaxSpeedMb() * 0.1f;
                    else maxUp += freq.getMaxSpeedMb() * 0.05f;
                }
            }
            
            // Signal degradation: -50 dBm is perfect (1.0x), -120 dBm is unusable (0.01x)
            float signalQuality = Math.max(0.01f, Math.min(1.0f, (bestSignal + 120) / 70.0f));
            maxDown = (int)(maxDown * signalQuality);
            maxUp = Math.max(1, (int)(maxUp * signalQuality));
            
            // Generate a virtual mobile IP address
            String mobileIp = "10.0." + (bestAntenna.getBlockPos().getX() % 255) + "." + (player.getId() % 255);
            PacketDistributor.sendToPlayer(player, new NetworkScanResponsePayload(true, bestAntenna.getAntennaName(), (int)bestSignal, finalTechLabel, mobileIp, bestAntenna.getBlockPos(), maxDown, maxUp));
        } else {
            PacketDistributor.sendToPlayer(player, new NetworkScanResponsePayload(false, "No Service", -120, "", "", BlockPos.ZERO, 0, 0));
        }
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
            Level level = context.player().level();
            BlockPos pos = payload.pos();
            if (level.isLoaded(pos)) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof com.florentdubut.telecom.block.entity.RouterBlockEntity router) {
                    router.setConfiguredMaxDown(payload.configuredMaxDown());
                    router.setConfiguredMaxUp(payload.configuredMaxUp());
                }
            }
        });
    }

    private static void handleStartSpeedtest(final com.florentdubut.telecom.network.packet.StartSpeedtestPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().level() instanceof ServerLevel serverLevel) {
                com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get(serverLevel);
                graph.startSpeedtest(payload.sourcePos(), payload.clientIp(), payload.targetDownBw(), payload.targetUpBw(), payload.extraPing());
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
}
