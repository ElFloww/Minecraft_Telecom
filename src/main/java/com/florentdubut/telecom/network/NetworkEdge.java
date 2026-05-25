package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;

public class NetworkEdge {
    private BlockPos nodeA;
    private BlockPos nodeB;
    private int bandwidthMax; // In MB/s or some arbitrary unit
    private int currentUsage; // To simulate saturation
    private int length; // Total length of cables, used for attenuation/latency
    private EdgeType type;
    private java.util.List<BlockPos> pathBlocks;

    public enum EdgeType {
        COPPER,
        FIBER,
        MEDIUM_FIBER,
        BIG_FIBER
    }

    public NetworkEdge(BlockPos nodeA, BlockPos nodeB, int bandwidthMax, int length, EdgeType type, java.util.List<BlockPos> pathBlocks) {
        this.nodeA = nodeA;
        this.nodeB = nodeB;
        this.bandwidthMax = bandwidthMax;
        this.length = length;
        this.type = type;
        this.pathBlocks = pathBlocks;
        this.currentUsage = 0;
    }
    
    public java.util.List<BlockPos> getPathBlocks() {
        return pathBlocks;
    }

    public BlockPos getNodeA() {
        return nodeA;
    }

    public BlockPos getNodeB() {
        return nodeB;
    }

    public int getBandwidthMax() {
        return bandwidthMax;
    }

    public int getCurrentUsage() {
        return currentUsage;
    }

    public void setCurrentUsage(int currentUsage) {
        this.currentUsage = currentUsage;
    }

    private int currentUsageDown;
    private int currentUsageUp;

    public int getCurrentUsageDown() {
        return currentUsageDown;
    }

    public void setCurrentUsageDown(int currentUsageDown) {
        this.currentUsageDown = currentUsageDown;
    }

    public int getCurrentUsageUp() {
        return currentUsageUp;
    }

    public void setCurrentUsageUp(int currentUsageUp) {
        this.currentUsageUp = currentUsageUp;
    }

    public int getLength() {
        return length;
    }

    public EdgeType getType() {
        return type;
    }
}
