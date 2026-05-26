package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;

public class NetworkNode {
    private BlockPos position;
    private String ipAddress;
    private NodeType type;
    private String networkCidr; // e.g. "10.1.0.0/16" for NRO, "10.1.2.0/24" for PM
    private int currentUsageDown = 0;
    private int currentUsageUp = 0;

    public enum NodeType {
        SERVER,
        ROUTER,
        ANTENNA,
        PHONE,
        NRO,
        NRA,
        PM,
        SR;

        /** Returns the allowed cable type(s) going TOWARD this node from a child */
        public NetworkEdge.EdgeType allowedCableFromChild() {
            return switch (this) {
                case SERVER  -> NetworkEdge.EdgeType.BIG_FIBER;
                case NRO     -> NetworkEdge.EdgeType.MEDIUM_FIBER; // child PM → NRO uses medium
                case PM      -> NetworkEdge.EdgeType.FIBER;         // child Router → PM uses small fiber or copper
                default      -> null;
            };
        }

        /** Returns the allowed cable type going toward the PARENT from this node */
        public NetworkEdge.EdgeType allowedCableToParent() {
            return switch (this) {
                case NRO     -> NetworkEdge.EdgeType.BIG_FIBER;    // NRO → Server/NRO uses big fiber
                case PM      -> NetworkEdge.EdgeType.MEDIUM_FIBER; // PM → NRO uses medium fiber
                case ROUTER  -> NetworkEdge.EdgeType.FIBER;        // Router → PM uses small fiber (or copper)
                case ANTENNA -> NetworkEdge.EdgeType.FIBER;        // Antenna → PM uses small fiber
                default      -> null;
            };
        }
    }

    public NetworkNode(BlockPos position, NodeType type) {
        this.position = position;
        this.type = type;
        this.ipAddress = null;
    }

    public BlockPos getPosition() {
        return position;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public NodeType getType() {
        return type;
    }

    public String getNetworkCidr() {
        return networkCidr;
    }

    public void setNetworkCidr(String cidr) {
        this.networkCidr = cidr;
    }

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
}
