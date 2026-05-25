package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;

public class NetworkEdge {
    private BlockPos nodeA;
    private BlockPos nodeB;
    private int bandwidthMax; // In MB/s or some arbitrary unit
    private int currentUsage; // To simulate saturation
    private int length; // Total length of cables, used for attenuation/latency
    private EdgeType type;

    public enum EdgeType {
        COPPER,
        FIBER
    }

    public NetworkEdge(BlockPos nodeA, BlockPos nodeB, int bandwidthMax, int length, EdgeType type) {
        this.nodeA = nodeA;
        this.nodeB = nodeB;
        this.bandwidthMax = bandwidthMax;
        this.length = length;
        this.type = type;
        this.currentUsage = 0;
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

    public int getLength() {
        return length;
    }

    public EdgeType getType() {
        return type;
    }
}
