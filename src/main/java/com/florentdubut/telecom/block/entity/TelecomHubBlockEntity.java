package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class TelecomHubBlockEntity extends BlockEntity {

    private final NetworkNode.NodeType hubType;

    public TelecomHubBlockEntity(BlockPos pos, BlockState blockState) {
        super(com.florentdubut.telecom.registry.ModBlockEntities.TELECOM_HUB.get(), pos, blockState);
        if (blockState.getBlock() == com.florentdubut.telecom.registry.ModBlocks.NRO_BLOCK.get()) {
            this.hubType = NetworkNode.NodeType.NRO;
        } else if (blockState.getBlock() == com.florentdubut.telecom.registry.ModBlocks.NRA_BLOCK.get()) {
            this.hubType = NetworkNode.NodeType.NRA;
        } else if (blockState.getBlock() == com.florentdubut.telecom.registry.ModBlocks.PM_BLOCK.get()) {
            this.hubType = NetworkNode.NodeType.PM;
        } else {
            this.hubType = NetworkNode.NodeType.SR;
        }
    }


    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get((net.minecraft.server.level.ServerLevel) level);
            if (graph.getNode(worldPosition) == null) {
                graph.addNode(new com.florentdubut.telecom.network.NetworkNode(worldPosition, hubType));
                com.florentdubut.telecom.network.NetworkTracer.scheduleRecalculation((net.minecraft.server.level.ServerLevel) level);
            }
        }
    }

    public void onPlaced() {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            if (graph.getNode(worldPosition) == null) {
                graph.addNode(new NetworkNode(worldPosition, hubType));
            }
            com.florentdubut.telecom.network.NetworkTracer.scheduleRecalculation(serverLevel);
        }
    }

    public void onRemoved() {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            graph.removeNode(worldPosition);
            com.florentdubut.telecom.network.NetworkTracer.scheduleRecalculation(serverLevel);
        }
    }
}
