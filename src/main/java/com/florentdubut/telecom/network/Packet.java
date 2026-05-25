package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;

public class Packet {
    private String sourceIp;
    private String destIp;
    private int size; // Size in kilobytes
    private String content; // E.g., "ping", "GET /"

    public Packet(String sourceIp, String destIp, int size, String content) {
        this.sourceIp = sourceIp;
        this.destIp = destIp;
        this.size = size;
        this.content = content;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public String getDestIp() {
        return destIp;
    }

    public int getSize() {
        return size;
    }

    public String getContent() {
        return content;
    }
}
