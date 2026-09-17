package com.florentdubut.telecom.client;

import com.florentdubut.telecom.network.TrafficSession;
import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Client-thread state, independent of the currently open screen. */
public final class ClientSpeedtestState {
    public static final int MAX_ENTRIES = 256;
    public static final long PENDING_TIMEOUT_NANOS = 3_000_000_000L;
    private static final UUID NO_SESSION = new UUID(0, 0);
    private static final Cache CACHE = new Cache(System::nanoTime);

    private ClientSpeedtestState() {}

    public record Key(String dimension, String deviceId) {
        public boolean matches(SpeedtestUpdatePayload payload) {
            return dimension.equals(payload.dimension()) && deviceId.equals(payload.deviceId());
        }
    }

    public record Snapshot(SpeedtestUpdatePayload payload, boolean pending) {
        public boolean active() {
            return pending || payload != null && !payload.terminal();
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
            if (entry == null) return new Snapshot(null, false);
            if (entry.pending && clock.getAsLong() - entry.pendingSince >= PENDING_TIMEOUT_NANOS) {
                entry.pending = false;
            }
            return new Snapshot(entry.payload, entry.pending);
        }

        boolean markPending(Object sourceConnection, Key key) {
            if (connection == null || sourceConnection != connection || get(key).active()) return false;
            Entry entry = entry(key);
            entry.pending = true;
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
                // A rejection acknowledges only the request, never cancels a real running session.
                if (entry.payload == null || entry.payload.terminal()) entry.payload = payload;
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

            entry.sequence = order;
            entry.payload = payload;
            entry.pending = false;
            return true;
        }

        private Entry entry(Key key) {
            Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
            if (entries.size() > MAX_ENTRIES) entries.pollFirstEntry();
            return entry;
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
        }
    }
}
