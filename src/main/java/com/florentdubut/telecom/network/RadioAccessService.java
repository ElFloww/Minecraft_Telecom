package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-thread radio attachment shared by scans, passive traffic and active tests. */
public final class RadioAccessService {
    static final int CACHE_TICKS = 20;
    static final int HANDOVER_TICKS = 40;
    static final int MAX_PLAYERS = 256;
    private static final int MAX_SOURCES = 128;
    private static final int MAX_NODES = 8192;
    private static final int MAX_PROBES = 65536;
    private static final Map<ServerLevel, LinkedHashMap<UUID, Attachment>> STATES = new WeakHashMap<>();

    private RadioAccessService() { }

    public record Snapshot(NetworkScanResponsePayload payload, Map<TelecomFrequency, Integer> downCaps,
                           Map<TelecomFrequency, Integer> upCaps) {
        public Snapshot {
            downCaps = Map.copyOf(downCaps);
            upCaps = Map.copyOf(upCaps);
        }
    }

    record Hit(BlockPos position, String name, TelecomFrequency frequency, float power,
               AntennaRadioConfig config) { }

    private static final class Attachment {
        final Selector selector = new Selector();
        Snapshot snapshot;
        long sampledAt;
        long fingerprint;
        BlockPos receiver;
    }

    /** A challenger must remain preferable across observations, not across repeated GUI requests. */
    static final class Selector {
        private BlockPos serving;
        private String technology;
        private BlockPos challenger;
        private String challengerTechnology;
        private long challengerSince;
        private long lastTick = Long.MIN_VALUE;

        Hit select(List<Hit> hits, long tick) {
            Hit best = hits.stream().max(RadioAccessService::compare).orElse(null);
            Hit current = hits.stream().filter(h -> h.position().equals(serving)
                    && h.frequency().getTechnology().equals(technology)).max(RadioAccessService::compare).orElse(null);
            if (best == null) {
                serving = null;
                challenger = null;
                lastTick = tick;
                return null;
            }
            if (current == null || current.power() < -110 || sameCell(best, current)) {
                return attach(best, tick);
            }
            Hit attached = current;
            best = hits.stream().filter(hit -> {
                int generation = hit.frequency().getTechnology().compareTo(attached.frequency().getTechnology());
                return generation > 0 ? hit.power() >= -105
                        : generation == 0 && hit.power() >= attached.power() + 3;
            }).max(RadioAccessService::compare).orElse(null);
            if (best == null) {
                challenger = null;
                lastTick = tick;
                return current;
            }
            if (!best.position().equals(challenger)
                    || !best.frequency().getTechnology().equals(challengerTechnology)
                    || tick < lastTick || tick - lastTick > HANDOVER_TICKS) {
                challenger = best.position();
                challengerTechnology = best.frequency().getTechnology();
                challengerSince = tick;
            }
            lastTick = tick;
            return tick - challengerSince >= HANDOVER_TICKS ? attach(best, tick) : current;
        }

        private Hit attach(Hit hit, long tick) {
            serving = hit.position();
            technology = hit.frequency().getTechnology();
            challenger = null;
            lastTick = tick;
            return hit;
        }

        private boolean sameCell(Hit a, Hit b) {
            return a.position().equals(b.position()) && a.frequency().getTechnology().equals(b.frequency().getTechnology());
        }
    }

    public static Snapshot scan(ServerPlayer player) {
        ServerLevel level = player.level();
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
        var players = STATES.computeIfAbsent(level, ignored -> new LinkedHashMap<>(16, .75f, true));
        Attachment attachment = players.computeIfAbsent(player.getUUID(), ignored -> new Attachment());
        while (players.size() > MAX_PLAYERS) players.remove(players.keySet().iterator().next());
        long tick = level.getGameTime();
        long fingerprint = graph.getTopologyRevision();
        BlockPos receiver = player.blockPosition().above();
        if (attachment.snapshot != null && fingerprint == attachment.fingerprint
                && receiver.equals(attachment.receiver)
                && tick >= attachment.sampledAt && tick - attachment.sampledAt < CACHE_TICKS) {
            return attachment.snapshot;
        }

        List<Hit> hits = new ArrayList<>();
        boolean unknown = false;
        boolean limited = false;
        int sources = 0;
        int probes = 0;
        int nodes = 0;
        for (NetworkNode node : graph.getNodes()) {
            if (++nodes > MAX_NODES) {
                limited = true;
                break;
            }
            if (node.getType() != NetworkNode.NodeType.ANTENNA || node.getFrequenciesMask() == 0
                    || node.getPosition().distSqr(receiver) > (double) SignalPropagator.MAX_RANGE * SignalPropagator.MAX_RANGE) continue;
            if (++sources > MAX_SOURCES) {
                limited = true;
                break;
            }
            BlockPos position = node.getPosition();
            AntennaBlockEntity antenna = null;
            if (level.isInWorldBounds(position) && level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) != null) {
                if (!(level.getBlockEntity(position) instanceof AntennaBlockEntity loaded)) continue;
                antenna = loaded;
            }
            String name = antenna == null ? "Antenna (" + position.getX() + ", " + position.getY() + ", " + position.getZ() + ")"
                    : antenna.getAntennaName();
            List<TelecomFrequency> frequencies = new ArrayList<>();
            for (TelecomFrequency frequency : TelecomFrequency.values()) {
                if (antenna != null ? antenna.isFrequencyEnabled(frequency)
                        : (node.getFrequenciesMask() & (1 << frequency.ordinal())) != 0) frequencies.add(frequency);
            }
            if (frequencies.isEmpty()) continue;
            AntennaRadioConfig config = node.getRadioConfig();
            var trace = new SignalPropagator.MultiTrace(position, receiver, frequencies, config);
            boolean finished = false;
            while (probes < MAX_PROBES) {
                probes += 256;
                if (trace.advance(level, 256, Long.MAX_VALUE)) {
                    finished = true;
                    break;
                }
            }
            if (!finished) {
                limited = true;
                break;
            }
            for (var result : trace.results()) {
                unknown |= !result.known;
                if (result.known && result.powerDbm > SignalPropagator.MIN_SIGNAL) {
                    hits.add(new Hit(position, name, result.frequency, result.powerDbm, config));
                }
            }
        }
        // Never publish a truncated prefix as a complete interference or attachment decision.
        Hit serving = limited ? null : attachment.selector.select(hits, tick);
        Snapshot snapshot;
        if (serving == null) {
            snapshot = new Snapshot(new NetworkScanResponsePayload(false, limited ? "Radio scan limit" : unknown ? "Terrain unavailable" : "No Service",
                    -120, "", "", BlockPos.ZERO, 0, 0, 0), Map.of(), Map.of());
        } else {
            Map<TelecomFrequency, Integer> down = new EnumMap<>(TelecomFrequency.class);
            Map<TelecomFrequency, Integer> up = new EnumMap<>(TelecomFrequency.class);
            List<String> labels = new ArrayList<>();
            int mask = 0;
            for (Hit hit : hits) {
                if (!hit.position().equals(serving.position())
                        || !hit.frequency().getTechnology().equals(serving.frequency().getTechnology())) continue;
                double quality = Math.max(.01, Math.min(1, (hit.power() + 120) / 70.0));
                double efficiency = interferenceEfficiency(hit, hits);
                int capacity = hit.config().capacityMbps(hit.frequency());
                // The simulation's integer-Mbps model retains a minimum usable carrier, including 2G.
                down.put(hit.frequency(), Math.max(1, (int) (capacity * quality * efficiency)));
                up.put(hit.frequency(), Math.max(1, (int) (capacity * AntennaRadioConfig.uploadRatio(hit.frequency()) * quality * efficiency)));
                if (!labels.contains(hit.frequency().getFrequencyLabel())) labels.add(hit.frequency().getFrequencyLabel());
                mask |= 1 << hit.frequency().ordinal();
            }
            String technology = serving.frequency().getTechnology() + (down.size() > 1 ? "+" : "")
                    + " (" + String.join(", ", labels) + ")";
            snapshot = new Snapshot(new NetworkScanResponsePayload(true, serving.name(), (int) serving.power(), technology,
                    graph.getMobileIp(player.getUUID()), serving.position(), down.values().stream().mapToInt(Integer::intValue).sum(),
                    up.values().stream().mapToInt(Integer::intValue).sum(), mask), down, up);
        }
        attachment.snapshot = snapshot;
        attachment.sampledAt = tick;
        attachment.fingerprint = fingerprint;
        attachment.receiver = receiver;
        return snapshot;
    }

    private static int compare(Hit left, Hit right) {
        return RadioSelection.compare(left.frequency(), left.power(), left.position(), right.frequency(), right.power(), right.position());
    }

    /** Simplified always-on spectral interference; predicted maps continue to display received power. */
    static double interferenceEfficiency(Hit desired, List<Hit> hits) {
        double width = channelWidthMhz(desired.frequency(), desired.config());
        double noise = Math.pow(10, (-174 + 10 * Math.log10(width * 1_000_000) + 7) / 10);
        double interference = noise;
        for (Hit other : hits) {
            if (other.position().equals(desired.position()) && other.frequency() == desired.frequency()) continue;
            double otherWidth = channelWidthMhz(other.frequency(), other.config());
            double overlap = Math.min(desired.frequency().getFrequencyMhz() + width / 2,
                    other.frequency().getFrequencyMhz() + otherWidth / 2)
                    - Math.max(desired.frequency().getFrequencyMhz() - width / 2,
                    other.frequency().getFrequencyMhz() - otherWidth / 2);
            if (overlap > 0) interference += Math.pow(10, other.power() / 10.0) * Math.min(1, overlap / otherWidth);
        }
        double sinr = Math.pow(10, desired.power() / 10.0) / interference;
        return Math.max(0, Math.min(1, Math.log1p(sinr) / Math.log(101)));
    }

    public static double channelWidthMhz(TelecomFrequency frequency, AntennaRadioConfig config) {
        return AntennaRadioConfig.referenceWidthMhz(frequency) * config.bandwidthPercent() / 100.0;
    }

    public static String unavailableReason(NetworkScanResponsePayload scan) {
        return switch (scan.name()) {
            case "Radio scan limit" -> "radio_limit";
            case "Terrain unavailable" -> "radio_unknown";
            default -> "radio_lost";
        };
    }

    public static void forget(UUID player) { STATES.values().forEach(state -> state.remove(player)); }
    public static void invalidate(ServerLevel level) {
        var players = STATES.get(level);
        if (players != null) players.values().forEach(attachment -> attachment.snapshot = null);
    }
    public static void unload(ServerLevel level) { STATES.remove(level); }
    public static void clear() { STATES.clear(); }
}
