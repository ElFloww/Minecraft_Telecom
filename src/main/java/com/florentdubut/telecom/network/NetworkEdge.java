package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;

public class NetworkEdge {
    private final BlockPos nodeA;
    private final BlockPos nodeB;
    private final int bandwidthMax; // Persisted nominal capacity in Mbps
    private int currentUsage; // Actual shared DOWN + UP usage in Mbps
    private final int length; // Total length of cables, used for attenuation/latency
    private final EdgeType type;
    private final java.util.List<BlockPos> pathBlocks;
    private final int effectiveBandwidth;
    private final int latencyMs;

    public enum EdgeType {
        COPPER,
        FIBER,
        MEDIUM_FIBER,
        BIG_FIBER,
        MICROWAVE;

        public int nominalBandwidthMbps() {
            return switch (this) {
                case COPPER -> 1_000;
                case FIBER -> 10_000;
                case MEDIUM_FIBER -> 100_000;
                case BIG_FIBER -> 1_000_000;
                case MICROWAVE -> 2_000;
            };
        }
    }

    public NetworkEdge(BlockPos nodeA, BlockPos nodeB, int bandwidthMax, int length, EdgeType type, java.util.List<BlockPos> pathBlocks) {
        this(nodeA, nodeB, bandwidthMax, length, type, pathBlocks, -1, 0);
    }

    private NetworkEdge(BlockPos nodeA, BlockPos nodeB, int bandwidthMax, int length, EdgeType type,
                        java.util.List<BlockPos> pathBlocks, int effectiveBandwidth, int latencyMs) {
        this.nodeA = nodeA.immutable();
        this.nodeB = nodeB.immutable();
        if (length < 0) throw new IllegalArgumentException("negative cable length");
        this.bandwidthMax = Math.clamp(bandwidthMax, 0, 1_000_000);
        this.length = length;
        this.type = type;
        this.pathBlocks = type == EdgeType.MICROWAVE || pathBlocks == null
                ? java.util.List.of() : pathBlocks.stream().map(BlockPos::immutable).toList();
        this.currentUsage = 0;
        this.effectiveBandwidth = effectiveBandwidth;
        this.latencyMs = latencyMs;
    }

    public static NetworkEdge microwave(BlockPos a, BlockPos b, int nominal, int effective, int length, int latencyMs) {
        if (latencyMs < 0) throw new IllegalArgumentException("negative microwave latency");
        int capacity = Math.clamp(nominal, 0, EdgeType.MICROWAVE.nominalBandwidthMbps());
        return new NetworkEdge(a, b, capacity, length, EdgeType.MICROWAVE, java.util.List.of(),
                Math.clamp(effective, 0, capacity), latencyMs);
    }

    /** Explicit microwave delay in milliseconds; wired paths retain their legacy latency formulas. */
    public int getLatencyMs() { return latencyMs; }
    
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

    public int getEffectiveBandwidthMbps() {
        if (effectiveBandwidth >= 0) return effectiveBandwidth;
        return type == EdgeType.COPPER
                ? (int) Math.min(bandwidthMax, Math.max(10L, 1000L - 2L * length))
                : bandwidthMax;
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
