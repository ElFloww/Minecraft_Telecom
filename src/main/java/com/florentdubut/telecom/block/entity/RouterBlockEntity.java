package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.registry.ModBlockEntities;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;

public class RouterBlockEntity extends BlockEntity {
    public RouterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ROUTER_BE.get(), pos, state);
    }

    public void onPlaced() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            NetworkNode node = new NetworkNode(worldPosition, NetworkNode.NodeType.ROUTER);
            graph.addNode(node);
            
            // TODO: Search for connected cables to establish edges
        }
    }

    public void onRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            graph.removeNode(worldPosition);
        }
    }
}
