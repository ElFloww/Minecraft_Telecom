package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.registry.ModBlockEntities;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;

public class ServerBlockEntity extends BlockEntity {

    public ServerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SERVER_BE.get(), pos, state);
    }

    public void onPlaced() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            NetworkNode node = new NetworkNode(worldPosition, NetworkNode.NodeType.SERVER);
            // Servers have the root IP
            node.setIpAddress("192.168.0.1");
            graph.addNode(node);
            com.florentdubut.telecom.network.NetworkTracer.recalculateNetwork(serverLevel);
        }
    }

    public void onRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            graph.removeNode(worldPosition);
            com.florentdubut.telecom.network.NetworkTracer.recalculateNetwork(serverLevel);
        }
    }
}
