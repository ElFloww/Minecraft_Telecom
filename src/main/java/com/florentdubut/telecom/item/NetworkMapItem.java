package com.florentdubut.telecom.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

public class NetworkMapItem extends Item {
    public NetworkMapItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            // Send request to server for map data
            PacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.RequestNetworkMapPayload());
            // Open screen immediately (it will say "Loading..." until data arrives)
            net.minecraft.client.Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.NetworkMapScreen());
        }
        return InteractionResultHolder.sidedSuccess(itemstack, level.isClientSide());
    }
}
