package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;

import java.util.UUID;

public class TrafficSession {
    public enum SessionState {
        PING,
        DOWNLOAD,
        UPLOAD,
        FINISHED,
        FAILED,
        REJECTED
    }

    private final UUID sessionId;
    private final String deviceId;
    private UUID ownerId;
    private final BlockPos sourcePos;
    private final BlockPos destPos;
    private SessionState state;
    private int ticksElapsed;
    private final int totalTicksPerPhase;
    private int targetDownBw; // requested download bandwidth
    private int targetUpBw;   // requested upload bandwidth
    private int actualBandwidth; // actual bandwidth achieved in the last tick
    private final String clientIp; // IP of the client (Router or Phone)
    private int pingMs;
    private int extraPing;
    private final boolean isPassive;
    // For mobile sessions: which antenna and frequencies are this session going through
    private BlockPos antennaPos = null;
    private int frequenciesMask = 0;
    private String failureReason = "";

    public TrafficSession(BlockPos sourcePos, BlockPos destPos, String clientIp, int targetDownBw, int targetUpBw, int totalTicksPerPhase, boolean isPassive, String deviceId) {
        this.sessionId = UUID.randomUUID();
        this.deviceId = deviceId;
        this.sourcePos = sourcePos.immutable();
        this.destPos = destPos.immutable();
        this.state = SessionState.PING;
        this.ticksElapsed = 0;
        this.targetDownBw = targetDownBw;
        this.targetUpBw = targetUpBw;
        this.totalTicksPerPhase = totalTicksPerPhase;
        this.clientIp = clientIp;
        this.actualBandwidth = 0;
        this.pingMs = 0;
        this.extraPing = 0;
        this.isPassive = isPassive;
    }

    public boolean isPassive() {
        return isPassive;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public static String routerDeviceId(BlockPos pos) { return "router:" + pos.asLong(); }

    public static String mobileDeviceId(UUID owner) { return "mobile:" + owner; }

    public String getDeviceId() { return deviceId; }

    public boolean isRouter() { return deviceId.startsWith("router:"); }

    public void fail() { fail(""); }

    public void fail(String reason) {
        failureReason = reason;
        state = SessionState.FAILED;
    }

    public String getFailureReason() { return failureReason; }

    public String getServerId() { return Long.toString(destPos.asLong()); }

    public String getServerName() {
        return "Server (" + destPos.getX() + "," + destPos.getY() + "," + destPos.getZ() + ")";
    }

    public boolean isTerminal() {
        return state == SessionState.FINISHED || state == SessionState.FAILED || state == SessionState.REJECTED;
    }

    public UUID getOwnerId() { return ownerId; }

    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public BlockPos getSourcePos() {
        return sourcePos;
    }

    public BlockPos getDestPos() {
        return destPos;
    }

    public SessionState getState() {
        return state;
    }

    public int getTicksElapsed() {
        return ticksElapsed;
    }

    public int getTotalTicksPerPhase() {
        return totalTicksPerPhase;
    }

    public int getTargetDownBw() {
        return targetDownBw;
    }
    
    public int getTargetUpBw() {
        return targetUpBw;
    }

    public int getActualBandwidth() {
        return actualBandwidth;
    }

    private int finalDownBw;
    private int finalUpBw;

    public void setActualBandwidth(int actualBandwidth) {
        this.actualBandwidth = actualBandwidth;
        if (state == SessionState.DOWNLOAD) finalDownBw = actualBandwidth;
        else if (state == SessionState.UPLOAD) finalUpBw = actualBandwidth;
    }

    public int getFinalDownBw() { return finalDownBw; }
    public int getFinalUpBw() { return finalUpBw; }

    public String getClientIp() {
        return clientIp;
    }
    
    public int getPingMs() {
        return pingMs;
    }
    
    public void setPingMs(int pingMs) {
        this.pingMs = pingMs;
    }
    
    public int getExtraPing() {
        return extraPing;
    }
    
    public void setExtraPing(int extraPing) {
        this.extraPing = extraPing;
    }

    public void tick() {
        if (isTerminal()) return;
        ticksElapsed++;
        int maxTicks = state == SessionState.PING ? Math.min(totalTicksPerPhase, 60) : totalTicksPerPhase;
        if (ticksElapsed >= maxTicks) {
            ticksElapsed = 0;
            switch (state) {
                case PING -> state = SessionState.DOWNLOAD;
                case DOWNLOAD -> state = SessionState.UPLOAD;
                case UPLOAD -> state = SessionState.FINISHED;
                default -> {}
            }
        }
    }

    public BlockPos getAntennaPos() { return antennaPos; }
    public void setAntennaPos(BlockPos pos) { this.antennaPos = pos; }

    public int getFrequenciesMask() { return frequenciesMask; }
    public void setFrequenciesMask(int mask) { this.frequenciesMask = mask; }
}
