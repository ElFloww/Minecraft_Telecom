package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.CoverageTilePayload;
import com.florentdubut.telecom.network.packet.RequestCoverageTilePayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Screen-owned cache: no world access, and no off-screen entries or polling. */
final class CoverageMapState {
    static final int TILE_BUDGET = 16;
    private static final int[] STEPS = {1, 8, 16, 32, 64, 128, 256, 512, 1024, 2048};
    private static final Set<String> STATUSES = Set.of("ready", "pending", "busy", "invalid", "limited");

    record Tile(int x, int z, int level, int step) {
        int span() { return level >= 0 ? 128 << level : 128 >> -level; }
    }

    private static final class Entry {
        CoverageTilePayload data;
        long nextAt;
        long validUntil;
        boolean requested;
    }

    private UUID viewId = UUID.randomUUID();
    private String dimension = "";
    private String modelRevision;
    private long nextSendAt;
    private final Map<Tile, Entry> cache = new LinkedHashMap<>();

    UUID viewId() { return viewId; }
    List<Tile> tiles() { return List.copyOf(cache.keySet()); }
    CoverageTilePayload data(Tile tile, long tick) {
        Entry entry = cache.get(tile);
        if (entry == null || entry.data == null) return null;
        return entry.data.status().equals("ready") && tick >= entry.validUntil ? null : entry.data;
    }

    void reset(String dimension, List<Tile> tiles) {
        if (tiles.size() > TILE_BUDGET) throw new IllegalArgumentException("Too many visible tiles");
        viewId = UUID.randomUUID();
        this.dimension = dimension;
        modelRevision = null;
        cache.clear();
        for (Tile tile : tiles) cache.put(tile, new Entry());
        // Preserve the global send deadline even during rapid view/filter changes.
    }

    void move(List<Tile> tiles) {
        if (tiles.size() > TILE_BUDGET) throw new IllegalArgumentException("Too many visible tiles");
        viewId = UUID.randomUUID();
        Map<Tile, Entry> visible = new LinkedHashMap<>();
        for (Tile tile : tiles) {
            Entry entry = cache.getOrDefault(tile, new Entry());
            entry.requested = false;
            visible.put(tile, entry);
        }
        cache.clear();
        cache.putAll(visible);
    }

    RequestCoverageTilePayload nextRequest(long tick, String height, String antenna, String technology, String band) {
        if (tick < nextSendAt || dimension.isEmpty()) return null;
        Map.Entry<Tile, Entry> candidate = null;
        for (var entry : cache.entrySet()) {
            if (entry.getValue().nextAt <= tick && (candidate == null
                    || entry.getValue().nextAt < candidate.getValue().nextAt)) candidate = entry;
        }
        if (candidate == null) return null;
        Tile tile = candidate.getKey();
        Entry entry = candidate.getValue();
        entry.requested = true;
        entry.nextAt = tick + 20; // Also retry a lost response without flooding the server.
        nextSendAt = tick + 4;
        return new RequestCoverageTilePayload(viewId, dimension, tile.x(), tile.z(), tile.step(),
                height, antenna, technology, band, tile.level());
    }

    boolean receive(CoverageTilePayload payload, long tick) {
        if (!viewId.equals(payload.viewId()) || !dimension.equals(payload.dimension())
                || !STATUSES.contains(payload.status()) || !Float.isFinite(payload.progress())
                || payload.progress() < 0 || payload.progress() > 1) return false;
        Tile tile = new Tile(payload.tileX(), payload.tileZ(), payload.level(), payload.step());
        Entry entry = cache.get(tile);
        if (entry == null || !entry.requested) return false;
        int side = tile.span() / tile.step();
        if (payload.cells().size() > side * side) return false;
        long originX = (long) tile.x() * tile.span(), originZ = (long) tile.z() * tile.span();
        for (var cell : payload.cells()) {
            long x = cell.x() - originX, z = cell.z() - originZ;
            if (x < 0 || x >= tile.span() || z < 0 || z >= tile.span()
                    || x % tile.step() != tile.step() / 2 || z % tile.step() != tile.step() / 2) return false;
        }
        // Busy/invalid responses can have no revision. They must not erase a known revision.
        if (!payload.modelRevision().isEmpty()) {
            if (modelRevision != null && !modelRevision.equals(payload.modelRevision())) {
                reset(dimension, tiles());
                entry = cache.get(tile);
                entry.requested = true;
                // A new UUID also rejects other in-flight replies from the obsolete model.
            }
            modelRevision = payload.modelRevision();
        }
        entry.data = payload;
        entry.validUntil = tick + 100;
        entry.nextAt = switch (payload.status()) {
            case "ready" -> tick + 100;
            case "invalid", "limited" -> Long.MAX_VALUE;
            case "busy" -> tick + 40;
            default -> tick + 20;
        };
        return true;
    }

    static double minimumZoom(int width, int height) {
        // Even at level 6, the entire viewport fits into at most four tiles per axis.
        return Math.max(0.05, Math.max(width, height) / (8192.0 * 3));
    }

    static List<Tile> visibleTiles(int width, int height, double offsetX, double offsetZ,
                                   double zoom, int requestedStep) {
        if (width <= 0 || height <= 0 || !Double.isFinite(zoom) || zoom <= 0
                || !Double.isFinite(offsetX) || !Double.isFinite(offsetZ)) return List.of();
        int level = -3;
        int span, minX, maxX, minZ, maxZ;
        while (true) {
            span = level >= 0 ? 128 << level : 128 >> -level;
            double size = span * zoom;
            minX = (int) Math.floor(-offsetX / size);
            maxX = (int) Math.ceil((width - offsetX) / size) - 1;
            minZ = (int) Math.floor(-offsetZ / size);
            maxZ = (int) Math.ceil((height - offsetZ) / size) - 1;
            long count = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
            if (count <= TILE_BUDGET) break;
            if (level == 6) return List.of(); // The screen clamps zoom instead of truncating the rectangle.
            level++;
        }
        int step = 1;
        for (int candidate : STEPS) {
            if (candidate >= Math.max(requestedStep, span / 16)) {
                step = candidate;
                break;
            }
        }
        List<Tile> tiles = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                long ox = (long) x * span, oz = (long) z * span;
                if (ox < -29_999_999L || oz < -29_999_999L
                        || ox + span - 1 > 29_999_999L || oz + span - 1 > 29_999_999L) continue;
                tiles.add(new Tile(x, z, level, step));
            }
        }
        double cx = (width / 2.0 - offsetX) / (span * zoom);
        double cz = (height / 2.0 - offsetZ) / (span * zoom);
        tiles.sort(Comparator.comparingDouble(tile -> Math.pow(tile.x() + 0.5 - cx, 2)
                + Math.pow(tile.z() + 0.5 - cz, 2)));
        return tiles;
    }

    static String signalState(CoverageTilePayload.Cell cell) {
        if ("none".equals(cell.state())) return "none";
        if (!"signal".equals(cell.state()) || !Float.isFinite(cell.powerDbm())) return "unknown";
        return cell.powerDbm() > -80 ? "strong" : cell.powerDbm() > -100 ? "medium"
                : cell.powerDbm() > -120 ? "weak" : "below";
    }

    static int color(String state) {
        return switch (state) {
            case "strong" -> 0xFF228B49;
            case "medium" -> 0xFFAC8706;
            case "weak" -> 0xFFB85510;
            case "below" -> 0xFFAB3030;
            case "none" -> 0xFF586473;
            case "unknown" -> 0xFF766897;
            default -> 0xFF293442;
        };
    }
}
