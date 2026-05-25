package com.florentdubut.telecom.block;

import com.florentdubut.telecom.block.entity.ServerBlockEntity;
import com.florentdubut.telecom.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ServerBlock extends Block implements EntityBlock {

    public ServerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ServerBlockEntity(pos, state);
    }

    @Override
    protected void onPlace(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ServerBlockEntity server) {
            server.onPlaced();
        }
    }

    @Override
    protected void onRemove(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ServerBlockEntity server) {
                server.onRemoved();
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get((net.minecraft.server.level.ServerLevel) level);
            
            int routers = 0;
            int antennas = 0;
            
            for (com.florentdubut.telecom.network.NetworkNode node : graph.getNodes()) {
                if (node.getType() == com.florentdubut.telecom.network.NetworkNode.NodeType.ROUTER) {
                    if (graph.calculatePathStats(pos, node.getPosition()) != null) routers++;
                } else if (node.getType() == com.florentdubut.telecom.network.NetworkNode.NodeType.ANTENNA) {
                    if (graph.calculatePathStats(pos, node.getPosition()) != null) antennas++;
                }
            }
            
            int phones = 0;
            for (net.minecraft.server.level.ServerPlayer p : serverPlayer.server.getPlayerList().getPlayers()) {
                if (p.getInventory().contains(new net.minecraft.world.item.ItemStack(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get()))) {
                    phones++;
                }
            }
            
            int bandwidth = (routers * 150) + (antennas * 50) + (phones * 15); // Simulated bandwidth
            
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer, new com.florentdubut.telecom.network.packet.ServerGuiSyncPayload(routers, antennas, phones, bandwidth));
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }
}
