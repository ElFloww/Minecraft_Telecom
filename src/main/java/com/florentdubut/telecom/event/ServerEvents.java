package com.florentdubut.telecom.event;

import com.florentdubut.telecom.TelecomMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = TelecomMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ServerEvents {
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!event.getEntity().level().isClientSide() && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get(player.serverLevel());
            // Delay by 100 ticks (5 seconds) to ensure chunks are fully loaded and ticking
            graph.scheduleDelayedRecalculation(100);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int globalDown = 0;
        int globalUp = 0;
        for (net.minecraft.server.level.ServerLevel level : event.getServer().getAllLevels()) {
            com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get(level);
            graph.tickTraffic(level);
            globalDown += graph.getTotalBandwidthDown();
            globalUp += graph.getTotalBandwidthUp();
        }

        tickCounter++;
        if (tickCounter % 4 == 0) {
            net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(new com.florentdubut.telecom.network.packet.ServerBandwidthUpdatePayload(globalDown, globalUp));
        }

        if (tickCounter >= 20) {
            tickCounter = 0;
            for (net.minecraft.server.level.ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                boolean hasPhone = player.getInventory().contains(new net.minecraft.world.item.ItemStack(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get()));
                if (hasPhone || player.getOffhandItem().is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get()) || player.getMainHandItem().is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get())) {
                    com.florentdubut.telecom.network.ModNetworking.scanForPlayer(player);
                }
            }
        }
    }
}
