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

        registrar.playToClient(
            NetworkScanResponsePayload.TYPE,
            NetworkScanResponsePayload.STREAM_CODEC,
            ModNetworking::handleNetworkScanResponse
        );
    }

    public static void scanForPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
        
        AntennaBlockEntity bestAntenna = null;
        int bestSignal = -1000;
        String finalTechLabel = "";

        for (NetworkNode node : graph.getNodes()) {
            if (node.getType() == NetworkNode.NodeType.ANTENNA) {
                BlockEntity be = level.getBlockEntity(node.getPosition());
                if (be instanceof AntennaBlockEntity antenna) {
                    
                    // Group valid frequencies by tech
                    java.util.Map<String, java.util.List<TelecomFrequency>> validFreqs = new java.util.HashMap<>();
                    java.util.Map<TelecomFrequency, Integer> signals = new java.util.HashMap<>();
                    
                    for (TelecomFrequency freq : TelecomFrequency.values()) {
                        if (antenna.isFrequencyEnabled(freq)) {
                            int signal = (int) com.florentdubut.telecom.network.SignalPropagator.calculateSignal(level, antenna.getBlockPos(), player.blockPosition().above(), freq).powerDbm;
                            
                            if (signal > -120) { // Valid connection
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
                            
                            int maxSignal = -1000;
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
            PacketDistributor.sendToPlayer(player, new NetworkScanResponsePayload(true, bestAntenna.getAntennaName(), bestSignal, finalTechLabel));
        } else {
            PacketDistributor.sendToPlayer(player, new NetworkScanResponsePayload(false, "No Service", -120, ""));
        }
    }

    private static void handleNetworkScanResponse(final NetworkScanResponsePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            com.florentdubut.telecom.client.ClientHooks.updateSmartphoneScreen(payload);
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
}
