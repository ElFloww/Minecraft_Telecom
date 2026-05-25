package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.registry.ModBlockEntities;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;

public class RouterBlockEntity extends BlockEntity {
    private int configuredMaxDown = 1000;
    private int configuredMaxUp = 1000;

    public RouterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ROUTER_BE.get(), pos, state);
    }

    public int getConfiguredMaxDown() { return configuredMaxDown; }
    public void setConfiguredMaxDown(int val) { this.configuredMaxDown = val; setChanged(); }

    public int getConfiguredMaxUp() { return configuredMaxUp; }
    public void setConfiguredMaxUp(int val) { this.configuredMaxUp = val; setChanged(); }

    @Override
    protected void saveAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("ConfiguredMaxDown", configuredMaxDown);
        tag.putInt("ConfiguredMaxUp", configuredMaxUp);
    }

    @Override
    protected void loadAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("ConfiguredMaxDown")) configuredMaxDown = tag.getInt("ConfiguredMaxDown");
        if (tag.contains("ConfiguredMaxUp")) configuredMaxUp = tag.getInt("ConfiguredMaxUp");
    }

    public void onPlaced() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            NetworkNode node = new NetworkNode(worldPosition, NetworkNode.NodeType.ROUTER);
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
