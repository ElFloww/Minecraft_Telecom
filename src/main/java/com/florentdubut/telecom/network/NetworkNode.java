package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;

public class NetworkNode {
    private BlockPos position;
    private String ipAddress;
    private NodeType type;

    public enum NodeType {
        SERVER,
        ROUTER,
        ANTENNA,
        PHONE,
        NRO,
        NRA,
        PM,
        SR
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
}
