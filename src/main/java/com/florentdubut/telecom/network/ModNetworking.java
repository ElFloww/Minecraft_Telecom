package com.florentdubut.telecom.network;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomFrequency;
import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import com.florentdubut.telecom.network.packet.NetworkScanRequestPayload;
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
            NetworkScanRequestPayload.TYPE,
            NetworkScanRequestPayload.STREAM_CODEC,
            ModNetworking::handleNetworkScanRequest
        );

        registrar.playToClient(
            NetworkScanResponsePayload.TYPE,
            NetworkScanResponsePayload.STREAM_CODEC,
            ModNetworking::handleNetworkScanResponse
        );
    }

    private static void handleNetworkScanRequest(final NetworkScanRequestPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ServerLevel level = player.serverLevel();
                TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
                
                AntennaBlockEntity bestAntenna = null;
                int bestSignal = -1000;
                TelecomFrequency bestTech = null;

                for (NetworkNode node : graph.getNodes()) {
                    if (node.getType() == NetworkNode.NodeType.ANTENNA) {
                        BlockEntity be = level.getBlockEntity(node.getPosition());
                        if (be instanceof AntennaBlockEntity antenna) {
                            for (TelecomFrequency freq : TelecomFrequency.values()) {
                                if (antenna.isFrequencyEnabled(freq)) {
                                    int signal = (int) com.florentdubut.telecom.network.SignalPropagator.calculateSignal(level, antenna.getBlockPos(), player.blockPosition(), freq).powerDbm;
                                    
                                    if (signal > -120) { // Valid connection
                                        if (bestTech == null || 
                                            (freq.getPerformanceScore() > bestTech.getPerformanceScore()) || 
                                            (freq.getPerformanceScore() == bestTech.getPerformanceScore() && signal > bestSignal)) {
                                            
                                            bestSignal = signal;
                                            bestAntenna = antenna;
                                            bestTech = freq;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (bestAntenna != null && bestTech != null) {
                    PacketDistributor.sendToPlayer(player, new NetworkScanResponsePayload(true, bestAntenna.getAntennaName(), bestSignal, bestTech.getTechnology() + " " + bestTech.getFrequencyLabel()));
                } else {
                    PacketDistributor.sendToPlayer(player, new NetworkScanResponsePayload(false, "No Service", -120, ""));
                }
            }
        });
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
