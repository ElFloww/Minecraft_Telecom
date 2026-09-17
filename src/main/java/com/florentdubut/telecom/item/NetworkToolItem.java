package com.florentdubut.telecom.item;

import com.florentdubut.telecom.network.ModNetworking;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class NetworkToolItem extends Item {

    public NetworkToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;

        BlockPos clickedPos = context.getClickedPos();
        BlockState state = level.getBlockState(clickedPos);
        
        if (state.is(ModBlocks.COPPER_CABLE.get()) || state.is(ModBlocks.FIBER_CABLE.get()) ||
            state.is(ModBlocks.MEDIUM_FIBER_CABLE.get()) || state.is(ModBlocks.BIG_FIBER_CABLE.get()) ||
            state.getBlock() instanceof com.florentdubut.telecom.block.TelecomHubBlock ||
            state.getBlock() instanceof com.florentdubut.telecom.block.RouterBlock || state.is(ModBlocks.SERVER.get()) || state.is(ModBlocks.ANTENNA.get())) {
            
            ServerLevel serverLevel = (ServerLevel) level;
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            
            var snapshot = ModNetworking.createNetworkToolSnapshot(graph, clickedPos);
            if (snapshot != null) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, snapshot);
                return InteractionResult.SUCCESS;
            } else {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.telecom.tool.disconnected"));
            }
        }
        
        return InteractionResult.PASS;
    }
}
