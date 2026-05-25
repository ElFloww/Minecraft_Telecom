package com.florentdubut.telecom.event;

import com.florentdubut.telecom.TelecomMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = TelecomMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ServerEvents {
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
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
