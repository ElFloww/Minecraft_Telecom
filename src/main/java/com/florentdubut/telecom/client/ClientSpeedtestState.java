package com.florentdubut.telecom.client;

import com.florentdubut.telecom.network.TrafficSession;
import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Client-thread state, independent of the currently open screen. */
public final class ClientSpeedtestState {
    public static final int MAX_ENTRIES = 256;
    public static final long PENDING_TIMEOUT_NANOS = 3_000_000_000L;
    public static final int MAX_POINTS_PER_PHASE = 120;
    private static final long RISE_NANOS = 250_000_000L;
    private static final UUID NO_SESSION = new UUID(0, 0);
    private static final Cache CACHE = new Cache(System::nanoTime);

    private ClientSpeedtestState() {}

    public record Key(String dimension, String deviceId) {
        public boolean matches(SpeedtestUpdatePayload payload) {
            return dimension.equals(payload.dimension()) && deviceId.equals(payload.deviceId());
        }
    }

    public record Point(int ticksElapsed, int bandwidth) {}

    public record Snapshot(SpeedtestUpdatePayload payload, boolean pending,
                           List<Point> download, List<Point> upload, int maxObserved,
                           long receivedAt, long staleAfter, double riseFrom, String errorCode, int confirmedElapsedTicks) {
        public boolean active() {
            return pending || payload != null && !payload.terminal();
        }

        public boolean waiting(long now) {
            return !pending && active() && now - receivedAt >= staleAfter;
        }

        public double instantaneous(long now) {
            if (pending || payload == null || payload.terminal() || "PING".equals(payload.state())) return 0;
            double target = Math.max(0, payload.actualBandwidth());
            double fraction = Math.clamp((double) (now - receivedAt) / RISE_NANOS, 0, 1);
            return Math.min(target, riseFrom + (target - riseFrom) * fraction);
        }

        public int phaseTicks() {
            if (payload == null) return 0;
            int duration = Math.max(1, payload.totalTicksPerPhase());
            return "PING".equals(payload.state()) ? Math.min(duration, 60) : duration;
        }

        public int totalTicks() {
            if (payload == null) return 0;
            int duration = Math.max(1, payload.totalTicksPerPhase());
            return Math.min(duration, 60) + 2 * duration;
        }

        public double elapsedTicks(long now) {
            if (pending || payload == null) return 0;
            if ("FINISHED".equals(payload.state())) return totalTicks();
            if (payload.terminal()) return confirmedElapsedTicks;
            int duration = Math.max(1, payload.totalTicksPerPhase());
            int offset = switch (payload.state()) {
                case "DOWNLOAD" -> Math.min(duration, 60);
                case "UPLOAD" -> Math.min(duration, 60) + duration;
                default -> 0;
            };
            // Do not advance through a server-owned phase boundary or a prolonged silence.
            double extra = Math.clamp(now - receivedAt, 0L, staleAfter) / 50_000_000.0;
            return Math.min(totalTicks() - 0.01, offset + Math.min(phaseTicks(), Math.max(0, payload.ticksElapsed()) + extra));
        }

        public int percent(long now) {
            if (payload == null || pending) return 0;
            if ("FINISHED".equals(payload.state())) return 100;
            if (elapsedTicks(now) < 0) return -1;
            return Math.min(99, (int) (100 * elapsedTicks(now) / totalTicks()));
        }
    }

    public static Key routerKey(String dimension, BlockPos pos) {
        return new Key(dimension, TrafficSession.routerDeviceId(pos));
    }

    public static Key mobileKey(String dimension, UUID playerId) {
        return new Key(dimension, TrafficSession.mobileDeviceId(playerId));
    }

    public static String currentDimension() {
        var level = Minecraft.getInstance().level;
        return level == null ? "" : level.dimension().identifier().toString();
    }

    private static Connection currentConnection() {
        var listener = Minecraft.getInstance().getConnection();
        return listener == null ? null : listener.getConnection();
    }

    public static void connected(Connection connection) {
        CACHE.connected(connection);
    }

    public static void clear() {
        CACHE.clear();
    }

    /** Called by networking before screen dispatch, including when no GUI is open. */
    static boolean accept(SpeedtestUpdatePayload payload) {
        return CACHE.accept(currentConnection(), payload);
    }

    public static boolean accept(Connection sourceConnection, SpeedtestUpdatePayload payload) {
        return CACHE.accept(sourceConnection, payload);
    }

    public static Snapshot get(Key key) {
        return CACHE.get(key);
    }

    public static boolean markPending(Key key) {
        return CACHE.markPending(currentConnection(), key);
    }

    // Package-visible to exercise connection boundaries and timeouts without a Minecraft client.
    static final class Cache {
        private final LongSupplier clock;
        private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
        private final LinkedHashMap<SessionKey, Long> sessionOrder = new LinkedHashMap<>(16, 0.75f, true);
        private Object connection;
        private long sequence;

        Cache(LongSupplier clock) {
            this.clock = clock;
        }

        void connected(Object connection) {
            clear();
            this.connection = connection;
        }

        void clear() {
            entries.clear();
            sessionOrder.clear();
            sequence = 0;
            connection = null;
        }

        Snapshot get(Key key) {
            Entry entry = entries.get(key);
            if (entry == null) return new Snapshot(null, false, List.of(), List.of(), 0, 0, PENDING_TIMEOUT_NANOS, 0, "", -1);
            if (entry.pending && clock.getAsLong() - entry.pendingSince >= PENDING_TIMEOUT_NANOS) {
                entry.pending = false;
                entry.errorCode = "timeout";
            }
            return entry.snapshot();
        }

        boolean markPending(Object sourceConnection, Key key) {
            if (connection == null || sourceConnection != connection || get(key).active()) return false;
            Entry entry = entry(key);
            entry.pending = true;
            entry.errorCode = "";
            entry.pendingSince = clock.getAsLong();
            entry.pendingAfterSequence = sequence;
            return true;
        }

        boolean accept(Object sourceConnection, SpeedtestUpdatePayload payload) {
            if (connection == null || sourceConnection != connection) return false;
            Key key = new Key(payload.dimension(), payload.deviceId());
            Entry entry = entry(key);
            if (NO_SESSION.equals(payload.sessionId())) {
                if (!"REJECTED".equals(payload.state())) return false;
                entry.pending = false;
                entry.errorCode = payload.errorCode();
                // A refusal acknowledges the request, not a new measured session. Keep its predecessor's results/history.
                if (entry.payload == null || NO_SESSION.equals(entry.payload.sessionId())) entry.payload = payload;
                return true;
            }

            SessionKey sessionKey = new SessionKey(key, payload.sessionId());
            Long order = sessionOrder.get(sessionKey);
            boolean sameSession = entry.payload != null && entry.payload.sessionId().equals(payload.sessionId());
            if (order == null) {
                // TCP orders first observations. An unseen terminal cannot replace a running
                // session, but a new request can fail before its first progress packet.
                if (payload.terminal() && entry.payload != null && !sameSession
                        && !entry.payload.terminal()) return false;
                order = sameSession ? entry.sequence : ++sequence;
                sessionOrder.put(sessionKey, order);
                if (sessionOrder.size() > MAX_ENTRIES) sessionOrder.pollFirstEntry();
            }
            if (order < entry.sequence || order == entry.sequence && !sameSession
                    || entry.pending && order <= entry.pendingAfterSequence
                    || sameSession && entry.payload.terminal()) return false;

            if (sameSession && !payload.terminal()) {
                int phase = phaseOrder(payload.state());
                int previous = phaseOrder(entry.payload.state());
                if (phase < previous || phase == previous && payload.ticksElapsed() <= entry.payload.ticksElapsed()) return false;
            }

            long now = clock.getAsLong();
            boolean samePhase = sameSession && entry.payload.state().equals(payload.state());
            double previousDisplay = samePhase ? entry.snapshot().instantaneous(now) : 0;
            if (!sameSession) {
                entry.download = List.of();
                entry.upload = List.of();
                entry.maxObserved = 0;
                entry.staleAfter = PENDING_TIMEOUT_NANOS;
                entry.confirmedElapsedTicks = -1;
            } else if (samePhase) {
                // Two observed packet intervals, capped at three seconds even after a pause.
                entry.staleAfter = Math.min(PENDING_TIMEOUT_NANOS, Math.max(500_000_000L, 2 * (now - entry.receivedAt)));
            }
            entry.riseFrom = Math.min(Math.max(0, payload.actualBandwidth()), previousDisplay);
            entry.receivedAt = now;
            int duration = Math.max(1, payload.totalTicksPerPhase());
            int pingTicks = Math.min(duration, 60);
            int offset = switch (payload.state()) {
                case "PING" -> 0;
                case "DOWNLOAD" -> pingTicks;
                case "UPLOAD" -> pingTicks + duration;
                default -> -1;
            };
            // Terminal packets do not identify the interrupted phase. Retain only same-session confirmations.
            if (offset >= 0) {
                entry.confirmedElapsedTicks = offset + Math.clamp(payload.ticksElapsed(), 0,
                        "PING".equals(payload.state()) ? pingTicks : duration);
            }
            if ("DOWNLOAD".equals(payload.state()) || "UPLOAD".equals(payload.state())) {
                var points = new ArrayList<>("DOWNLOAD".equals(payload.state()) ? entry.download : entry.upload);
                points.add(new Point(payload.ticksElapsed(), Math.max(0, payload.actualBandwidth())));
                if (points.size() > MAX_POINTS_PER_PHASE) points.removeFirst();
                if ("DOWNLOAD".equals(payload.state())) entry.download = List.copyOf(points);
                else entry.upload = List.copyOf(points);
                entry.maxObserved = Math.max(entry.maxObserved, Math.max(0, payload.actualBandwidth()));
            }

            entry.sequence = order;
            entry.payload = payload;
            entry.errorCode = payload.errorCode();
            entry.pending = false;
            return true;
        }

        private Entry entry(Key key) {
            Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
            if (entries.size() > MAX_ENTRIES) entries.pollFirstEntry();
            return entry;
        }

        private static int phaseOrder(String state) {
            return switch (state) {
                case "PING" -> 0;
                case "DOWNLOAD" -> 1;
                case "UPLOAD" -> 2;
                default -> 3;
            };
        }

        int size() {
            return entries.size();
        }

        int sessionCount() {
            return sessionOrder.size();
        }

        private record SessionKey(Key key, UUID sessionId) {}

        private static final class Entry {
            private SpeedtestUpdatePayload payload;
            private long sequence;
            private boolean pending;
            private long pendingSince;
            private long pendingAfterSequence;
            private List<Point> download = List.of();
            private List<Point> upload = List.of();
            private int maxObserved;
            private long receivedAt;
            private long staleAfter = PENDING_TIMEOUT_NANOS;
            private double riseFrom;
            private String errorCode = "";
            private int confirmedElapsedTicks = -1;

            private Snapshot snapshot() {
                return new Snapshot(payload, pending, download, upload, maxObserved, receivedAt, staleAfter, riseFrom, errorCode, confirmedElapsedTicks);
            }
        }
    }
}
