package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.network.NetworkDiagnostics;

import com.florentdubut.telecom.registry.ModBlockEntities;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

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
    protected void saveAdditional(ValueOutput tag) {
        super.saveAdditional(tag);
        tag.putInt("LastDownBw", lastDownBw);
        tag.putInt("LastUpBw", lastUpBw);
        tag.putInt("LastPing", lastPing);
    }

    @Override
    protected void loadAdditional(ValueInput tag) {
        super.loadAdditional(tag);
        lastDownBw = tag.getIntOr("LastDownBw", 0);
        lastUpBw = tag.getIntOr("LastUpBw", 0);
        lastPing = tag.getIntOr("LastPing", 0);
    }


    @Override
    public void onLoad() {
        super.onLoad();
        if (!isRemoved()) {
            registerNodeIfMissing();
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        onRemoved();
    }

    private void registerNodeIfMissing() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            NetworkNode node = graph.getNode(worldPosition);
            if (node == null) {
                node = new NetworkNode(worldPosition, NetworkNode.NodeType.ROUTER);
                node.setCapacityDown(getConfiguredMaxDown());
                node.setCapacityUp(getConfiguredMaxUp());
                graph.addNode(node);
                com.florentdubut.telecom.network.NetworkTracer.scheduleRecalculation(serverLevel, NetworkDiagnostics.Cause.NODE_LOAD);
            } else if (node.getType() == NetworkNode.NodeType.ROUTER) {
                boolean requiresSync = node.requiresCapacitySync();
                int down = requiresSync ? getConfiguredMaxDown() : Math.min(node.getCapacityDown(), getConfiguredMaxDown());
                int up = requiresSync ? getConfiguredMaxUp() : Math.min(node.getCapacityUp(), getConfiguredMaxUp());
                if (requiresSync || node.getCapacityDown() != down || node.getCapacityUp() != up) {
                    node.setCapacityDown(down);
                    node.setCapacityUp(up);
                    node.setCapacitySyncRequired(false);
                    graph.setDirty();
                }
            }
        }
    }

    public void onRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            graph.removeNode(worldPosition);
            com.florentdubut.telecom.network.NetworkTracer.scheduleRecalculation(serverLevel, NetworkDiagnostics.Cause.NODE_REMOVE);
        }
    }
}
