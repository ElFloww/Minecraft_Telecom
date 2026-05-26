package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;

import java.util.UUID;

public class TrafficSession {
    public enum SessionState {
        PING,
        DOWNLOAD,
        UPLOAD,
        FINISHED
    }

    private final UUID sessionId;
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
    // For mobile sessions: which antenna and frequency is this session going through
    private BlockPos antennaPos = null;
    private TelecomFrequency frequencyUsed = null;

    public TrafficSession(BlockPos sourcePos, BlockPos destPos, String clientIp, int targetDownBw, int targetUpBw, int totalTicksPerPhase, boolean isPassive) {
        this.sessionId = UUID.randomUUID();
        this.sourcePos = sourcePos;
        this.destPos = destPos;
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
        ticksElapsed++;
        if (ticksElapsed >= totalTicksPerPhase) {
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

    public TelecomFrequency getFrequencyUsed() { return frequencyUsed; }
    public void setFrequencyUsed(TelecomFrequency freq) { this.frequencyUsed = freq; }
}
