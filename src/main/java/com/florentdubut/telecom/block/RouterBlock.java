package com.florentdubut.telecom.block;

import com.florentdubut.telecom.block.entity.RouterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class RouterBlock extends Block implements EntityBlock {
    public RouterBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RouterBlockEntity(pos, state);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RouterBlockEntity router) {
                router.onPlaced();
            }
        }
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get(serverPlayer.serverLevel());
            com.florentdubut.telecom.network.NetworkNode node = graph.getNode(pos);
            
            if (node != null) {
                boolean isConnected = node.getIpAddress() != null;
                
                // Calculate ping and bandwidth to server if connected
                int ping = 0;
                int bandwidth = 0;
                
                if (isConnected) {
                    com.florentdubut.telecom.network.NetworkNode serverNode = null;
                    for (com.florentdubut.telecom.network.NetworkNode n : graph.getNodes()) {
                        if (n.getType() == com.florentdubut.telecom.network.NetworkNode.NodeType.SERVER) {
                            serverNode = n;
                            break;
                        }
                    }
                    
                    if (serverNode != null) {
                        com.florentdubut.telecom.network.TelecomNetworkGraph.PathStats stats = graph.calculatePathStats(pos, serverNode.getPosition());
                        if (stats != null) {
                            ping = stats.pingMs();
                            bandwidth = stats.bandwidthMbps();
                        } else {
                            isConnected = false;
                        }
                    } else {
                        isConnected = false;
                    }
                }
                
                int maxDown = 1000;
                int maxUp = 1000;
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof RouterBlockEntity routerBE) {
                    maxDown = routerBE.getConfiguredMaxDown();
                    maxUp = routerBE.getConfiguredMaxUp();
                }

                String safeIp = node.getIpAddress() != null ? node.getIpAddress() : "";
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    serverPlayer, 
                    new com.florentdubut.telecom.network.packet.RouterGuiSyncPayload(pos, isConnected, safeIp, ping, bandwidth, maxDown, maxUp)
                );
            }
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock()) {
            if (!level.isClientSide()) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof RouterBlockEntity router) {
                    router.onRemoved();
                }
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }
}
