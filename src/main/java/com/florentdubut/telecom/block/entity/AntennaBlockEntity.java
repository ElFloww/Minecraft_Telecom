package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.registry.ModBlockEntities;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;

public class AntennaBlockEntity extends BlockEntity {
    private boolean is4GEnabled = true;
    private boolean is5GEnabled = false;

    public AntennaBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ANTENNA_BE.get(), pos, state);
    }

    public void onPlaced() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            NetworkNode node = new NetworkNode(worldPosition, NetworkNode.NodeType.ANTENNA);
            graph.addNode(node);
        }
    }

    public void onRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            graph.removeNode(worldPosition);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("is4GEnabled", is4GEnabled);
        tag.putBoolean("is5GEnabled", is5GEnabled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        is4GEnabled = tag.getBoolean("is4GEnabled");
        is5GEnabled = tag.getBoolean("is5GEnabled");
    }

    public boolean is4GEnabled() {
        return is4GEnabled;
    }

    public boolean is5GEnabled() {
        return is5GEnabled;
    }
}
