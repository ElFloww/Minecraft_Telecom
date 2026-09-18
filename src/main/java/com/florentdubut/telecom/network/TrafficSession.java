package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;

import java.util.UUID;
import java.util.Map;

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
    private BlockPos sourcePos;
    private final BlockPos destPos;
    private SessionState state;
    private int ticksElapsed;
    private final int totalTicksPerPhase;
    private int targetDownBw; // requested download bandwidth
    private int targetUpBw;   // requested upload bandwidth
    private int actualBandwidth; // actual bandwidth achieved in the last tick
    private int measuredBandwidth;
    private SessionState measuredPhase;
    private final String clientIp; // IP of the client (Router or Phone)
    private int pingMs;
    private int extraPing;
    private final boolean isPassive;
    // For mobile sessions: which antenna and frequencies are this session going through
    private BlockPos antennaPos = null;
    private int frequenciesMask = 0;
    private Map<TelecomFrequency, Integer> radioDownCaps = Map.of();
    private Map<TelecomFrequency, Integer> radioUpCaps = Map.of();
    private boolean radioCapsConfigured;
    private String radioTechnology = "";
    private String failureReason = "";

    public TrafficSession(BlockPos sourcePos, BlockPos destPos, String clientIp, int targetDownBw, int targetUpBw, int totalTicksPerPhase, boolean isPassive, String deviceId) {
        this(UUID.randomUUID(), sourcePos, destPos, clientIp, targetDownBw, targetUpBw, totalTicksPerPhase, isPassive, deviceId);
    }

    TrafficSession(UUID sessionId, BlockPos sourcePos, BlockPos destPos, String clientIp, int targetDownBw, int targetUpBw, int totalTicksPerPhase, boolean isPassive, String deviceId) {
        this.sessionId = sessionId;
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
        clearCurrentBandwidth();
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

    /** Last allocation sample, not the live counters cleared during a topology recalculation. */
    public int getMeasuredBandwidth() {
        return !isTerminal() && state == measuredPhase ? measuredBandwidth : 0;
    }

    int getRequestedBandwidth(int hardwareMax) {
        if (state != SessionState.DOWNLOAD && state != SessionState.UPLOAD) return 0;
        // A real manual mobile test starts with scan maxima, not a permanent user demand limit.
        // Passive traffic and synthetic/wired fixtures retain their explicit original targets.
        boolean adaptiveMobile = radioCapsConfigured && !isPassive && ownerId != null && !isRouter();
        int target = adaptiveMobile ? hardwareMax : state == SessionState.UPLOAD ? targetUpBw : targetDownBw;
        int ceiling = Math.max(0, Math.min(target, hardwareMax));
        if (radioCapsConfigured) {
            Map<TelecomFrequency, Integer> caps = state == SessionState.UPLOAD ? radioUpCaps : radioDownCaps;
            long total = 0;
            for (var entry : caps.entrySet()) {
                if ((frequenciesMask & (1 << entry.getKey().ordinal())) != 0) total += entry.getValue();
            }
            ceiling = (int) Math.min(ceiling, total);
        }
        if (isPassive || ceiling == 0) return ceiling;

        // Mix both UUID halves and the phase; sampling never advances a random generator.
        long seed = sessionId.getMostSignificantBits() ^ Long.rotateLeft(sessionId.getLeastSignificantBits(), 32)
                ^ (state == SessionState.UPLOAD ? 0x9e3779b97f4a7c15L : 0x632be59bd9b4e019L);
        seed = (seed ^ (seed >>> 30)) * 0xbf58476d1ce4e5b9L;
        seed = (seed ^ (seed >>> 27)) * 0x94d049bb133111ebL;
        seed ^= seed >>> 31;
        double phase37 = (seed & 0xffffffffL) * 0x1.0p-32;
        double phase83 = (seed >>> 32) * 0x1.0p-32;
        double plateau = 0.97
                + 0.018 * StrictMath.sin(2 * Math.PI * (ticksElapsed / 37.0 + phase37))
                + 0.012 * StrictMath.sin(2 * Math.PI * (ticksElapsed / 83.0 + phase83));
        double load = 0.35 + (plateau - 0.35) * Math.min(1.0, ticksElapsed / 20.0);
        // Round up so a real 1 Mbps demand survives, but never exceed the path/target ceiling.
        return Math.min(ceiling, (int) Math.ceil(ceiling * load));
    }

    public void clearCurrentBandwidth() {
        actualBandwidth = 0;
    }

    private int finalDownBw;
    private int finalUpBw;
    private long downBandwidthSum;
    private long upBandwidthSum;
    private int downSamples;
    private int upSamples;

    public void setActualBandwidth(int actualBandwidth) {
        if (state != SessionState.DOWNLOAD && state != SessionState.UPLOAD) return;
        this.actualBandwidth = actualBandwidth;
        measuredBandwidth = actualBandwidth;
        measuredPhase = state;
        // Sum allocations, including zero ticks, rather than averaging rounded running means.
        if (state == SessionState.DOWNLOAD) {
            downBandwidthSum += actualBandwidth;
            downSamples++;
            finalDownBw = (int) ((downBandwidthSum + downSamples / 2) / downSamples);
        } else {
            upBandwidthSum += actualBandwidth;
            upSamples++;
            finalUpBw = (int) ((upBandwidthSum + upSamples / 2) / upSamples);
        }
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
                case UPLOAD -> {
                    state = SessionState.FINISHED;
                    clearCurrentBandwidth();
                }
                default -> {}
            }
        }
    }

    public BlockPos getAntennaPos() { return antennaPos; }
    public void setAntennaPos(BlockPos pos) { this.antennaPos = pos; }

    public int getFrequenciesMask() { return frequenciesMask; }
    public void setFrequenciesMask(int mask) { this.frequenciesMask = mask; }

    /** Refresh reception without restarting the phase, changing identity, or reselecting the server. */
    public void updateRadioAttachment(BlockPos pos, int mask, Map<TelecomFrequency, Integer> downCaps,
                                      Map<TelecomFrequency, Integer> upCaps) {
        Map<TelecomFrequency, Integer> down = Map.copyOf(downCaps);
        Map<TelecomFrequency, Integer> up = Map.copyOf(upCaps);
        for (int cap : down.values()) if (cap < 0 || cap > 1_000_000) throw new IllegalArgumentException("invalid radio ceiling");
        for (int cap : up.values()) if (cap < 0 || cap > 1_000_000) throw new IllegalArgumentException("invalid radio ceiling");
        String technology = "";
        for (TelecomFrequency frequency : TelecomFrequency.values()) {
            if ((mask & (1 << frequency.ordinal())) != 0 && down.getOrDefault(frequency, 0) > 0
                    && frequency.getTechnology().compareTo(technology) > 0) technology = frequency.getTechnology();
        }
        if (!technology.isEmpty()) {
            // Keep initial same-technology jitter; a technology handover uses a stable radio latency.
            if (!radioTechnology.isEmpty() && !radioTechnology.equals(technology)) {
                extraPing = switch (technology) {
                    case "5G" -> 15;
                    case "4G" -> 40;
                    case "3G" -> 95;
                    default -> 300;
                };
            }
            radioTechnology = technology;
        }
        sourcePos = pos.immutable();
        antennaPos = sourcePos;
        frequenciesMask = mask & ((1 << TelecomFrequency.values().length) - 1);
        radioDownCaps = down;
        radioUpCaps = up;
        radioCapsConfigured = true;
    }

    public Map<TelecomFrequency, Integer> getRadioDownCaps() { return radioDownCaps; }
    public Map<TelecomFrequency, Integer> getRadioUpCaps() { return radioUpCaps; }
    public boolean hasRadioCaps() { return radioCapsConfigured; }
}
