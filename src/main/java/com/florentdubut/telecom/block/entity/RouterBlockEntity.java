package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.registry.ModBlockEntities;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;

public class RouterBlockEntity extends BlockEntity {
    private int lastDownBw = 0;
    private int lastUpBw = 0;
    private int lastPing = 0;

    public RouterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ROUTER_BE.get(), pos, state);
    }

    public int getConfiguredMaxDown() { 
        if (getBlockState().getBlock() instanceof com.florentdubut.telecom.block.RouterBlock rb) return rb.getMaxDown();
        return 1000;
    }

    public int getConfiguredMaxUp() { 
        if (getBlockState().getBlock() instanceof com.florentdubut.telecom.block.RouterBlock rb) return rb.getMaxUp();
        return 1000;
    }
    
    public int getLastDownBw() { return lastDownBw; }
    public int getLastUpBw() { return lastUpBw; }
    public int getLastPing() { return lastPing; }
    
    public void setLastSpeedtestResults(int down, int up, int ping) {
        this.lastDownBw = down;
        this.lastUpBw = up;
        this.lastPing = ping;
        setChanged();
    }

    @Override
    protected void saveAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("LastDownBw", lastDownBw);
        tag.putInt("LastUpBw", lastUpBw);
        tag.putInt("LastPing", lastPing);
    }

    @Override
    protected void loadAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("LastDownBw")) lastDownBw = tag.getInt("LastDownBw");
        if (tag.contains("LastUpBw")) lastUpBw = tag.getInt("LastUpBw");
        if (tag.contains("LastPing")) lastPing = tag.getInt("LastPing");
    }

    public void onPlaced() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            NetworkNode node = new NetworkNode(worldPosition, NetworkNode.NodeType.ROUTER);
            graph.addNode(node);
            
            com.florentdubut.telecom.network.NetworkTracer.scheduleRecalculation(serverLevel);
        }
    }

    public void onRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            graph.removeNode(worldPosition);
            com.florentdubut.telecom.network.NetworkTracer.scheduleRecalculation(serverLevel);
        }
    }
}
