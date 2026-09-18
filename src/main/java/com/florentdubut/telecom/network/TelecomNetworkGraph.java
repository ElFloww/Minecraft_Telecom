package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public class TelecomNetworkGraph extends SavedData {
    private static final String DATA_NAME = "telecom_network";

    private final Map<BlockPos, NetworkNode> nodes = new HashMap<>();
    private final List<NetworkEdge> edges = new ArrayList<>();
    private final Map<Long, Integer> recordedCoverage = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<java.util.UUID, Integer> mobileAddresses = new HashMap<>();
    private int nextMobileAddress = 1;
    private long topologyRevision;
    private final NetworkDiagnostics diagnostics = new NetworkDiagnostics();

    public NetworkDiagnostics getDiagnostics() { return diagnostics; }

    public long getTopologyRevision() { return topologyRevision; }

    // Keep the existing NBT layout so worlds created before the API migration still load.
    public static final Codec<TelecomNetworkGraph> CODEC = CompoundTag.CODEC.comapFlatMap(tag -> {
        try {
            return DataResult.success(load(tag));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "Invalid telecom network: " + e.getMessage());
        }
    }, TelecomNetworkGraph::save);
    public static final SavedDataType<TelecomNetworkGraph> TYPE =
            new SavedDataType<>(DATA_NAME, TelecomNetworkGraph::new, CODEC);

    public TelecomNetworkGraph() {}

    private static TelecomNetworkGraph load(CompoundTag tag) {
        int version = tag.getIntOr("SchemaVersion", 0);
        if (version < 0 || version > 1) {
            throw new IllegalArgumentException("unsupported schema version " + version);
        }
        int capacityVersion = readInt(tag, "CapacityModelVersion", 0);
        if (capacityVersion < 0 || capacityVersion > 1) {
            throw new IllegalArgumentException("unsupported capacity model version " + capacityVersion);
        }
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        
        ListTag nodesTag = tag.getListOrEmpty("Nodes");
        for (int i = 0; i < nodesTag.size(); i++) {
            CompoundTag nodeTag = nodesTag.getCompound(i).orElseThrow(() -> new IllegalArgumentException("invalid node"));
            BlockPos pos = nodeTag.read("Pos", BlockPos.CODEC).orElseThrow(() -> new IllegalArgumentException("invalid node position"));
            NetworkNode.NodeType type = NetworkNode.NodeType.valueOf(nodeTag.getStringOr("Type", ""));
            NetworkNode node = new NetworkNode(pos, type);
            if (nodeTag.contains("IP")) {
                node.setIpAddress(nodeTag.getStringOr("IP", ""));
            }
            if (nodeTag.contains("CIDR")) {
                node.setNetworkCidr(nodeTag.getStringOr("CIDR", ""));
            }
            if (nodeTag.contains("FreqMask")) {
                node.setFrequenciesMask(nodeTag.getIntOr("FreqMask", 0));
            }
            node.setRadioConfig(AntennaRadioConfig.read(nodeTag));
            node.setMicrowaveConfig(MicrowaveConfig.read(nodeTag));
            if (capacityVersion == 1 || type == NetworkNode.NodeType.ROUTER) {
                node.setCapacityDown(readInt(nodeTag, "CapDown", type.defaultCapacityMbps()));
                node.setCapacityUp(readInt(nodeTag, "CapUp", type.defaultCapacityMbps()));
            }
            node.setCapacitySyncRequired(type == NetworkNode.NodeType.ROUTER &&
                    (capacityVersion == 0 || nodeTag.getBooleanOr("CapacityNeedsSync", false)));
            graph.nodes.put(pos, node);
        }

        ListTag edgesTag = tag.getListOrEmpty("Edges");
        for (int i = 0; i < edgesTag.size(); i++) {
            CompoundTag edgeTag = edgesTag.getCompound(i).orElseThrow(() -> new IllegalArgumentException("invalid edge"));
            NetworkEdge.EdgeType type = NetworkEdge.EdgeType.valueOf(edgeTag.getStringOr("Type", ""));
            if (type == NetworkEdge.EdgeType.MICROWAVE) continue;
            BlockPos nodeA = edgeTag.read("NodeA", BlockPos.CODEC).orElseThrow(() -> new IllegalArgumentException("invalid edge source"));
            BlockPos nodeB = edgeTag.read("NodeB", BlockPos.CODEC).orElseThrow(() -> new IllegalArgumentException("invalid edge target"));
            int bandwidthMax = readInt(edgeTag, "BandwidthMax", 0);
            int length = readInt(edgeTag, "Length", Integer.MAX_VALUE);
            java.util.List<BlockPos> pathBlocks = new java.util.ArrayList<>();
            if (edgeTag.contains("PathBlocks")) {
                long[] blocks = edgeTag.getLongArray("PathBlocks").orElse(new long[0]);
                for (long l : blocks) {
                    pathBlocks.add(BlockPos.of(l));
                }
            }
            NetworkEdge edge = new NetworkEdge(nodeA, nodeB, bandwidthMax, length, type, pathBlocks);
            graph.edges.add(edge);
        }

        
        if (tag.contains("CoverageKeys") && tag.contains("CoverageValues")) {
            long[] keys = tag.getLongArray("CoverageKeys").orElse(new long[0]);
            int[] values = tag.getIntArray("CoverageValues").orElse(new int[0]);
            if (keys.length == values.length) {
                for (int j = 0; j < keys.length; j++) {
                    graph.recordedCoverage.put(keys[j], values[j]);
                }
            }
        }
        java.util.Set<Integer> allocatedHosts = new java.util.HashSet<>();
        for (net.minecraft.nbt.Tag entry : tag.getListOrEmpty("MobileAddresses")) {
            CompoundTag address = entry.asCompound().orElseThrow(() -> new IllegalArgumentException("invalid mobile lease"));
            java.util.UUID owner = java.util.UUID.fromString(address.getStringOr("Owner", ""));
            int host = address.getIntOr("Host", 0);
            if (host < 1 || host >= 0xFFFFF || graph.mobileAddresses.containsKey(owner)
                    || !allocatedHosts.add(host)) {
                throw new IllegalArgumentException("invalid or duplicate mobile lease");
            }
            graph.mobileAddresses.put(owner, host);
            graph.nextMobileAddress = Math.max(graph.nextMobileAddress, host + 1);
        }
        graph.ensureFixedAddresses();
        if (capacityVersion == 0) graph.setDirty();
        return graph;
    }

    private static int readInt(CompoundTag tag, String key, int fallback) {
        if (!tag.contains(key)) return fallback;
        if (!(tag.get(key) instanceof net.minecraft.nbt.IntTag)) {
            throw new IllegalArgumentException("invalid integer " + key);
        }
        return tag.getIntOr(key, fallback);
    }

    private CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("SchemaVersion", 1);
        tag.putInt("CapacityModelVersion", 1);
        ListTag nodesTag = new ListTag();
        for (NetworkNode node : nodes.values()) {
            CompoundTag nodeTag = new CompoundTag();
            nodeTag.store("Pos", BlockPos.CODEC, node.getPosition());
            nodeTag.putString("Type", node.getType().name());
            if (node.getIpAddress() != null) {
                nodeTag.putString("IP", node.getIpAddress());
            }
            if (node.getNetworkCidr() != null) {
                nodeTag.putString("CIDR", node.getNetworkCidr());
            }
            nodeTag.putInt("FreqMask", node.getFrequenciesMask());
            node.getRadioConfig().writeTo(nodeTag);
            node.getMicrowaveConfig().writeTo(nodeTag);
            nodeTag.putInt("CapDown", node.getCapacityDown());
            nodeTag.putInt("CapUp", node.getCapacityUp());
            nodeTag.putBoolean("CapacityNeedsSync", node.requiresCapacitySync());
            nodesTag.add(nodeTag);
        }
        tag.put("Nodes", nodesTag);

        ListTag edgesTag = new ListTag();
        for (NetworkEdge edge : edges) {
            if (edge.getType() == NetworkEdge.EdgeType.MICROWAVE) continue;
            CompoundTag edgeTag = new CompoundTag();
            edgeTag.store("NodeA", BlockPos.CODEC, edge.getNodeA());
            edgeTag.store("NodeB", BlockPos.CODEC, edge.getNodeB());
            edgeTag.putInt("BandwidthMax", edge.getBandwidthMax());
            edgeTag.putInt("Length", edge.getLength());
            edgeTag.putString("Type", edge.getType().name());
            if (edge.getPathBlocks() != null) {
                long[] blocks = new long[edge.getPathBlocks().size()];
                for(int j = 0; j < blocks.length; j++) {
                    blocks[j] = edge.getPathBlocks().get(j).asLong();
                }
                edgeTag.putLongArray("PathBlocks", blocks);
            }
            edgesTag.add(edgeTag);
        }
        tag.put("Edges", edgesTag);

        long[] covKeys = new long[recordedCoverage.size()];
        int[] covValues = new int[recordedCoverage.size()];
        int idx = 0;
        for (Map.Entry<Long, Integer> entry : recordedCoverage.entrySet()) {
            covKeys[idx] = entry.getKey();
            covValues[idx] = entry.getValue();
            idx++;
        }
        tag.putLongArray("CoverageKeys", covKeys);
        tag.putIntArray("CoverageValues", covValues);

        ListTag addresses = new ListTag();
        mobileAddresses.forEach((owner, host) -> {
            CompoundTag address = new CompoundTag();
            address.putString("Owner", owner.toString());
            address.putInt("Host", host);
            addresses.add(address);
        });
        tag.put("MobileAddresses", addresses);

        return tag;
    }

    public static TelecomNetworkGraph get(ServerLevel level) {
        return get(level.getDataStorage());
    }

    static TelecomNetworkGraph get(net.minecraft.world.level.storage.DimensionDataStorage storage) {
        TelecomNetworkGraph existing = storage.get(TYPE);
        if (existing != null) return existing;
        // Minecraft treats a failed decode like a missing file. Never replace such a file.
        try {
            storage.readTagFromDisk(DATA_NAME, null, 0);
        } catch (java.nio.file.NoSuchFileException missing) {
            TelecomNetworkGraph graph = new TelecomNetworkGraph();
            storage.set(TYPE, graph);
            return graph;
        } catch (java.io.IOException | RuntimeException unreadable) {
            throw new IllegalStateException("Cannot read telecom_network.dat; refusing to overwrite it. Restore a backup before continuing.", unreadable);
        }
        throw new IllegalStateException("Cannot decode existing telecom_network.dat; refusing to overwrite it. Check the schema or restore a backup.");
    }

    public String getMobileIp(java.util.UUID owner) {
        Integer host = mobileAddresses.get(owner);
        if (host == null) {
            if (nextMobileAddress >= 0xFFFFF) {
                throw new IllegalStateException("Mobile IPv4 pool exhausted");
            }
            host = nextMobileAddress++;
            mobileAddresses.put(owner, host);
            setDirty();
        }
        // A private /12 distinct from the existing fixed-network 10/8 allocation.
        return "172." + (16 + (host >>> 16)) + "." + ((host >>> 8) & 255) + "." + (host & 255);
    }

    /** Node IPs are the persisted leases. Connectivity never determines or releases an address. */
    public void ensureFixedAddresses() {
        List<NetworkNode> ordered = nodes.values().stream()
                .sorted(java.util.Comparator.comparingLong(node -> node.getPosition().asLong())).toList();
        java.util.Set<Integer> allocated = new java.util.HashSet<>();
        List<NetworkNode> missing = new ArrayList<>();
        for (NetworkNode node : ordered) {
            int host = fixedHost(node.getIpAddress());
            if (host < 1 || !allocated.add(host)) missing.add(node);
        }
        if ((long) allocated.size() + missing.size() > 0xFFFFFE) {
            throw new IllegalStateException("Fixed IPv4 pool exhausted");
        }
        int next = 1;
        boolean changed = false;
        // Reserve every existing unique lease before filling holes, including disconnected nodes.
        for (NetworkNode node : missing) {
            while (allocated.contains(next)) next++;
            allocated.add(next);
            String address = "10." + (next >>> 16) + "." + ((next >>> 8) & 255) + "." + (next & 255);
            node.setIpAddress(address);
            node.setNetworkCidr(address + "/32");
            next++;
            changed = true;
        }
        for (NetworkNode node : ordered) {
            if (node.getNetworkCidr() == null || node.getNetworkCidr().isBlank()) {
                node.setNetworkCidr(node.getIpAddress() + "/32");
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    private static int fixedHost(String address) {
        if (address == null) return -1;
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("10")) return -1;
        int host = 0;
        try {
            for (int i = 1; i < 4; i++) {
                int octet = Integer.parseInt(parts[i]);
                if (octet < 0 || octet > 255 || !Integer.toString(octet).equals(parts[i])) return -1;
                host = (host << 8) | octet;
            }
        } catch (NumberFormatException invalid) {
            return -1;
        }
        return host > 0 && host < 0xFFFFFF ? host : -1;
    }

    private boolean needsRecalculation = false;
    private int delayedRecalculationTimer = -1;

    public void markForRecalculation() {
        markForRecalculation(NetworkDiagnostics.Cause.UNSPECIFIED);
    }

    public void markForRecalculation(NetworkDiagnostics.Cause cause) {
        this.needsRecalculation = true;
        diagnostics.request(cause, false);
    }

    public void scheduleDelayedRecalculation(int ticks) {
        scheduleDelayedRecalculation(ticks, NetworkDiagnostics.Cause.UNSPECIFIED);
    }

    public void scheduleDelayedRecalculation(int ticks, NetworkDiagnostics.Cause cause) {
        if (this.delayedRecalculationTimer < 0 || this.delayedRecalculationTimer > ticks) {
            this.delayedRecalculationTimer = ticks;
        }
        diagnostics.request(cause, true);
    }

    
    public Map<Long, Integer> getRecordedCoverage() {
        return recordedCoverage;
    }

    public void addCoverageRecord(BlockPos pos, int techId, int signalLevel) {
        long key = pos.asLong();
        int value = (techId << 8) | signalLevel;
        Integer existing = recordedCoverage.get(key);
        if (existing == null || existing < value) { // Basic update rule
            recordedCoverage.put(key, value);
            setDirty();
        }
    }

    public void addNode(NetworkNode node) {
        NetworkNode previous = nodes.get(node.getPosition());
        if (previous != null && previous.getType() == node.getType() && node.getIpAddress() == null) {
            node.setIpAddress(previous.getIpAddress());
            node.setNetworkCidr(previous.getNetworkCidr());
        }
        if (previous != null && previous.getType() == node.getType()) {
            node.setCapacityDown(previous.getCapacityDown());
            node.setCapacityUp(previous.getCapacityUp());
            node.setCapacitySyncRequired(previous.requiresCapacitySync());
        }
        nodes.put(node.getPosition(), node);
        topologyRevision++;
        pathCache.clear();
        setDirty();
    }

    public void removeNode(BlockPos pos) {
        nodes.remove(pos);
        topologyRevision++;
        edges.removeIf(edge -> edge.getNodeA().equals(pos) || edge.getNodeB().equals(pos));
        pathCache.clear();
        setDirty();
    }

    public void addEdge(NetworkEdge edge) {
        edges.add(edge);
        topologyRevision++;
        pathCache.clear();
        setDirty();
    }

    public void removeEdgeBetween(BlockPos a, BlockPos b) {
        topologyRevision++;
        edges.removeIf(edge -> (edge.getNodeA().equals(a) && edge.getNodeB().equals(b)) ||
                               (edge.getNodeA().equals(b) && edge.getNodeB().equals(a)));
        pathCache.clear();
        setDirty();
    }

    public int getActualBlockUsageDown(BlockPos pos) {
        return actualBlockUsageDown.getOrDefault(pos, 0);
    }
    public int getActualBlockUsageUp(BlockPos pos) {
        return actualBlockUsageUp.getOrDefault(pos, 0);
    }

    public int getActualBlockCapacityMbps(BlockPos pos) {
        PhysicalNetwork physical = physicalNetwork();
        return physical == null ? 0 : physical.capacities.getOrDefault(pos, 0);
    }

    private final Map<BlockPos, Integer> actualBlockUsageDown = new HashMap<>();
    private final Map<BlockPos, Integer> actualBlockUsageUp = new HashMap<>();
    private final BandwidthAllocator bandwidthAllocator = new BandwidthAllocator();
    public static final int MAX_PHYSICAL_PATH_REFERENCES = 262_144;
    public static final int MAX_ALLOCATION_RESOURCE_REFERENCES = 262_144;
    private long physicalRevision = -1;
    private PhysicalNetwork physicalCache;

    private record NodeBudget(BlockPos pos, boolean upload) {}
    private record RadioBudget(BlockPos pos, TelecomFrequency frequency) {}
    private final Map<RadioBudget, Double> actualRadioUsage = new HashMap<>();
    private record PhysicalNetwork(Map<BlockPos, Integer> capacities,
                                   Map<NetworkEdge, java.util.Set<BlockPos>> blocks,
                                   Map<NetworkEdge, Integer> edgeCapacities) {
        int capacity(NetworkEdge edge) {
            return edgeCapacities.get(edge);
        }
    }

    private PhysicalNetwork physicalNetwork() {
        if (physicalRevision == topologyRevision) return physicalCache;
        physicalRevision = topologyRevision;
        physicalCache = null; // Also cache quota failures, until topology changes.
        if (nodes.size() > MAX_SPEEDTEST_CATALOGUE_NODES || edges.size() > MAX_SPEEDTEST_CATALOGUE_EDGES) return null;
        long references = 0;
        for (NetworkEdge edge : edges) {
            references += edge.getPathBlocks().size();
            if (references > MAX_PHYSICAL_PATH_REFERENCES) return null;
        }
        java.util.Set<BlockPos> endpoints = new java.util.HashSet<>(nodes.keySet());
        for (NetworkEdge edge : edges) {
            endpoints.add(edge.getNodeA());
            endpoints.add(edge.getNodeB());
        }
        Map<BlockPos, Integer> capacities = new HashMap<>();
        Map<NetworkEdge, java.util.Set<BlockPos>> blocks = new HashMap<>();
        for (NetworkEdge edge : edges) {
            java.util.Set<BlockPos> physical = new java.util.LinkedHashSet<>(edge.getPathBlocks());
            physical.removeAll(endpoints);
            blocks.put(edge, physical);
            for (BlockPos pos : physical) capacities.merge(pos, edge.getEffectiveBandwidthMbps(), Math::min);
        }
        Map<NetworkEdge, Integer> edgeCapacities = new HashMap<>();
        blocks.forEach((edge, physical) -> {
            int capacity = edge.getEffectiveBandwidthMbps();
            for (BlockPos pos : physical) capacity = Math.min(capacity, capacities.get(pos));
            edgeCapacities.put(edge, capacity);
        });
        physicalCache = new PhysicalNetwork(capacities, blocks, edgeCapacities);
        return physicalCache;
    }

    private void resetUsage() {
        for (TrafficSession session : activeSessions) session.clearCurrentBandwidth();
        actualBlockUsageDown.clear();
        actualBlockUsageUp.clear();
        actualRadioUsage.clear();
        totalBandwidthDown = 0;
        totalBandwidthUp = 0;
        for (NetworkEdge edge : edges) {
            edge.setCurrentUsage(0);
            edge.setCurrentUsageDown(0);
            edge.setCurrentUsageUp(0);
        }
        for (NetworkNode node : nodes.values()) {
            node.setCurrentUsageDown(0);
            node.setCurrentUsageUp(0);
        }
    }

    private final List<TrafficSession> activeSessions = new ArrayList<>();
    private record CompletedSpeedtest(TrafficSession session, SpeedtestUpdatePayload update) {}

    private final Map<String, CompletedSpeedtest> lastResults = new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CompletedSpeedtest> eldest) {
            return size() > 256;
        }
    };
    private int totalBandwidthUp = 0;
    private int totalBandwidthDown = 0;

    public static final int MAX_SPEEDTEST_SERVERS = 128;
    private static final int MAX_SPEEDTEST_CATALOGUE_NODES = 8192;
    private static final int MAX_SPEEDTEST_CATALOGUE_EDGES = 16384;

    public static class SpeedtestCatalogueLimitException extends RuntimeException {
        public SpeedtestCatalogueLimitException() {
            super("Speedtest catalogue graph limit exceeded");
        }
    }

    public record SpeedtestStartResult(TrafficSession session, String error) {
        public boolean accepted() { return session != null; }
    }

    public List<SpeedtestServerOption> getSpeedtestServers(BlockPos sourcePos, int extraPing) {
        if (nodes.size() > MAX_SPEEDTEST_CATALOGUE_NODES || edges.size() > MAX_SPEEDTEST_CATALOGUE_EDGES) {
            throw new SpeedtestCatalogueLimitException();
        }
        if (sourcePos == null || !nodes.containsKey(sourcePos) || extraPing < 0 || extraPing > 60_000) return List.of();
        PhysicalNetwork physical = physicalNetwork();
        if (physical == null) throw new SpeedtestCatalogueLimitException();
        // Preserve edge insertion order, matching findShortestPath's first-discovered BFS paths.
        Map<BlockPos, List<NetworkEdge>> adjacency = new HashMap<>();
        for (NetworkEdge edge : edges) {
            BlockPos a = edge.getNodeA();
            BlockPos b = edge.getNodeB();
            adjacency.computeIfAbsent(a, key -> new ArrayList<>()).add(edge);
            if (!a.equals(b)) adjacency.computeIfAbsent(b, key -> new ArrayList<>()).add(edge);
        }
        Map<BlockPos, PathMetrics> visited = new HashMap<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        visited.put(sourcePos, initialMetrics(sourcePos));
        queue.add(sourcePos);
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            PathMetrics metrics = visited.get(current);
            for (NetworkEdge edge : adjacency.getOrDefault(current, List.of())) {
                BlockPos neighbor = edge.getNodeA().equals(current) ? edge.getNodeB() : edge.getNodeA();
                if (visited.containsKey(neighbor)) continue;
                visited.put(neighbor, metrics.append(edge, nodes.get(neighbor), physical.capacity(edge)));
                queue.addLast(neighbor);
            }
        }
        List<SpeedtestServerOption> servers = new ArrayList<>();
        for (NetworkNode node : nodes.values()) {
            if (node.getType() != NetworkNode.NodeType.SERVER) continue;
            BlockPos pos = node.getPosition();
            PathMetrics metrics = visited.get(pos);
            PathStats stats = metrics == null ? null : metrics.stats();
            servers.add(new SpeedtestServerOption(Long.toString(pos.asLong()),
                    "Server (" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")",
                    stats == null ? -1 : stats.pingMs() + extraPing, stats != null,
                    stats == null ? 0 : stats.bandwidthMbps(), stats == null ? "server_unavailable" : ""));
        }
        servers.sort(java.util.Comparator.comparing((SpeedtestServerOption server) -> !server.available())
                .thenComparingInt(SpeedtestServerOption::estimatedPingMs)
                .thenComparingLong(server -> Long.parseLong(server.id())));
        return List.copyOf(servers.subList(0, Math.min(servers.size(), MAX_SPEEDTEST_SERVERS)));
    }

    public void startSpeedtest(BlockPos sourcePos, String clientIp, int targetDownBw, int targetUpBw, int extraPing, int frequenciesMask, int durationTicks, boolean isPassive, @org.jetbrains.annotations.Nullable net.minecraft.server.level.ServerPlayer player) {
        SpeedtestStartResult result = startSpeedtest(sourcePos, clientIp, targetDownBw, targetUpBw, extraPing, frequenciesMask, durationTicks, isPassive, player, "");
        if (result.accepted()) return;
        NetworkNode source = nodes.get(sourcePos);
        String deviceId = source != null && source.getType() == NetworkNode.NodeType.ROUTER
                ? TrafficSession.routerDeviceId(sourcePos)
                : player != null ? TrafficSession.mobileDeviceId(player.getUUID()) : "mobile-ip:" + clientIp;
        String message = switch (result.error()) {
            case "device_busy" -> "A speedtest is already running on this device.";
            case "session_limit" -> "Speedtest rejected: session limit reached.";
            case "network_limit" -> "Speedtest rejected: network resource limit reached.";
            case "no_server", "server_unavailable" -> "Failed to start Speedtest: No complete path to a Server was found.";
            default -> "Speedtest rejected: invalid request.";
        };
        rejectSpeedtest(player, isPassive, clientIp, deviceId, message, result.error());
    }

    public SpeedtestStartResult startSpeedtest(BlockPos sourcePos, String clientIp, int targetDownBw, int targetUpBw, int extraPing, int frequenciesMask, int durationTicks, boolean isPassive, @org.jetbrains.annotations.Nullable net.minecraft.server.level.ServerPlayer player, String serverId) {
        NetworkNode source = nodes.get(sourcePos);
        String deviceId = source != null && source.getType() == NetworkNode.NodeType.ROUTER
                ? TrafficSession.routerDeviceId(sourcePos)
                : player != null ? TrafficSession.mobileDeviceId(player.getUUID()) : "mobile-ip:" + clientIp;
        if (sourcePos == null || source == null || clientIp == null || clientIp.isBlank() || clientIp.length() > 45
                || serverId == null || serverId.length() > 24
                || targetDownBw < 1 || targetDownBw > 1_000_000 || targetUpBw < 1 || targetUpBw > 1_000_000
                || extraPing < 0 || extraPing > 60_000 || durationTicks < 1 || durationTicks > 12_000
                || (frequenciesMask & ~((1 << TelecomFrequency.values().length) - 1)) != 0) {
            return new SpeedtestStartResult(null, "invalid_request");
        }
        TrafficSession existing = getSessionByDeviceId(deviceId);
        if (existing != null && (isPassive || !existing.isPassive())) {
            return new SpeedtestStartResult(null, "device_busy");
        }
        if (existing == null && activeSessions.size() >= 256) {
            return new SpeedtestStartResult(null, "session_limit");
        }
        PhysicalNetwork physical = physicalNetwork();
        if (physical == null) return new SpeedtestStartResult(null, "network_limit");
        
        // Resolve the destination before replacing any passive traffic on this device.
        NetworkNode bestServer = null;
        PathStats bestStats = null;
        
        if (!serverId.isEmpty()) {
            try {
                long packedPos = Long.parseLong(serverId);
                if (!Long.toString(packedPos).equals(serverId)) return new SpeedtestStartResult(null, "server_unavailable");
                bestServer = nodes.get(BlockPos.of(packedPos));
            } catch (NumberFormatException exception) {
                return new SpeedtestStartResult(null, "server_unavailable");
            }
            if (bestServer == null || bestServer.getType() != NetworkNode.NodeType.SERVER) {
                return new SpeedtestStartResult(null, "server_unavailable");
            }
            bestStats = calculatePathStats(sourcePos, bestServer.getPosition(), physical);
        } else {
            for (NetworkNode node : nodes.values()) {
                if (node.getType() != NetworkNode.NodeType.SERVER) continue;
                PathStats stats = calculatePathStats(sourcePos, node.getPosition(), physical);
                if (stats != null && (bestStats == null || stats.pingMs() < bestStats.pingMs()
                        || (stats.pingMs() == bestStats.pingMs() && node.getPosition().asLong() < bestServer.getPosition().asLong()))) {
                    bestStats = stats;
                    bestServer = node;
                }
            }
        }
        
        if (bestServer != null && bestStats != null) {
            if (existing != null) {
                activeSessions.remove(existing);
                existing.clearCurrentBandwidth();
                bandwidthAllocator.forget(existing.getSessionId());
            }
            TrafficSession session = new TrafficSession(sourcePos, bestServer.getPosition(), clientIp, targetDownBw, targetUpBw, durationTicks, isPassive, deviceId);
            if (player != null) session.setOwnerId(player.getUUID());
            session.setExtraPing(extraPing);
            session.setPingMs(bestStats.pingMs() + extraPing);
            session.setAntennaPos(sourcePos); // Used by mobile sessions to map back to antenna
            session.setFrequenciesMask(frequenciesMask);
            activeSessions.add(session);
            return new SpeedtestStartResult(session, "");
        } else {
            return new SpeedtestStartResult(null, serverId.isEmpty() ? "no_server" : "server_unavailable");
        }
    }

    private void rejectSpeedtest(net.minecraft.server.level.ServerPlayer player, boolean passive, String ip, String deviceId, String message, String errorCode) {
        if (player == null || passive) return;
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new SpeedtestUpdatePayload(
                ip == null ? "" : ip.substring(0, Math.min(ip.length(), 45)), "REJECTED", 0, 0, 0, 0,
                player.level().dimension().identifier().toString(), deviceId.substring(0, Math.min(deviceId.length(), 128)),
                new java.util.UUID(0, 0), 0, 0, "", "", errorCode));
    }

    private void sendSessionUpdate(ServerLevel level, TrafficSession session) {
        SpeedtestUpdatePayload update = new SpeedtestUpdatePayload(
                session.getClientIp(), session.getState().name(), session.getPingMs(),
                session.getMeasuredBandwidth(), session.getTicksElapsed(), session.getTotalTicksPerPhase(),
                level.dimension().identifier().toString(), session.getDeviceId(), session.getSessionId(),
                session.getFinalDownBw(), session.getFinalUpBw(), session.getServerId(), session.getServerName(), session.getFailureReason());
        if (!session.isPassive() && session.isTerminal()) {
            lastResults.remove(session.getDeviceId());
            lastResults.put(session.getDeviceId(), new CompletedSpeedtest(session, update));
        }
        if (!session.isPassive() && session.getOwnerId() != null) {
            var owner = level.getServer().getPlayerList().getPlayer(session.getOwnerId());
            if (owner != null) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(owner, update);
        }
    }

    public SpeedtestUpdatePayload getLastResultByDeviceId(String deviceId) {
        CompletedSpeedtest result = lastResults.get(deviceId);
        return result == null ? null : result.update();
    }

    /** Display only manual speedtests, preferring active over completed or failed sessions. */
    public TrafficSession getLatestSessionByDeviceId(String deviceId) {
        TrafficSession active = getSessionByDeviceId(deviceId);
        if (active != null && !active.isPassive()) return active;
        CompletedSpeedtest result = lastResults.get(deviceId);
        return result == null ? null : result.session();
    }

    private void tickPassiveTraffic(ServerLevel level) {
        // Routers
        if (!nodes.isEmpty()) {
            for (NetworkNode node : nodes.values()) {
                if (node.getType() == NetworkNode.NodeType.ROUTER) {
                    // Reduced probability to 0.5% (was 1%)
                    if (Math.random() < 0.005) {
                        boolean hasSession = getSessionByDeviceId(TrafficSession.routerDeviceId(node.getPosition())) != null;
                        if (!hasSession) {
                            // Reduced bandwidth consumption significantly
                            int randDown = 1 + (int)(Math.random() * 200); // 1 to 200 Mbps (was 10-2000)
                            int randUp = 1 + (int)(Math.random() * 50); // 1 to 50 Mbps (was 5-500)
                            startSpeedtest(node.getPosition(), node.getIpAddress() != null ? node.getIpAddress() : "0.0.0.0", randDown, randUp, 0, 0, 100, true, null);
                        }
                    }
                }
            }
        }

        // Smartphones (Players)
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            boolean hasPhone = player.getInventory().contains(new net.minecraft.world.item.ItemStack(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get())) ||
                               player.getOffhandItem().is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get()) || 
                               player.getMainHandItem().is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get());
            
            // Reduced probability to 0.5%
            if (hasPhone && Math.random() < 0.005) {
                if (getSessionByDeviceId(TrafficSession.mobileDeviceId(player.getUUID())) != null) continue;
                var snapshot = RadioAccessService.scan(player);
                var scan = snapshot.payload();
                if (scan.found()) {
                    int randDown = 1 + (int)(Math.random() * 20);
                    int randUp = 1 + (int)(Math.random() * 5);
                    int extraPing = 20 + (int)(Math.random() * 50);
                    var result = startSpeedtest(scan.antennaPos(), getMobileIp(player.getUUID()), randDown, randUp,
                            extraPing, scan.frequenciesMask(), 100, true, player, "");
                    if (result.accepted()) result.session().updateRadioAttachment(scan.antennaPos(),
                            scan.frequenciesMask(), snapshot.downCaps(), snapshot.upCaps());
                }
            }
        }
    }

    public void tickTraffic(ServerLevel level) {
        diagnostics.nextTick();
        MicrowaveLinkService.tick(level, this);
        tickPassiveTraffic(level);
        resetUsage();

        if (delayedRecalculationTimer > 0) {
            delayedRecalculationTimer--;
        } else if (delayedRecalculationTimer == 0) {
            delayedRecalculationTimer = -1;
            needsRecalculation = true;
            diagnostics.delayedReady();
        }

        if (needsRecalculation) {
            needsRecalculation = false;
            diagnostics.runScheduled(() -> NetworkTracer.recalculateNetwork(level));
            return; // Skip this tick, it will resume next tick
        }
        if (activeSessions.isEmpty()) return;

        List<TrafficSession> toRemove = new ArrayList<>();
        Map<TrafficSession, BandwidthAllocator.Request> requests = new java.util.LinkedHashMap<>();
        PhysicalNetwork physical = physicalNetwork();
        Map<Object, Integer> capacities = new HashMap<>();
        int retainedResourceReferences = 0;

        for (TrafficSession session : activeSessions) {
            if (physical == null) {
                session.fail("network_limit");
                sendSessionUpdate(level, session);
                toRemove.add(session);
                continue;
            }
            if (!session.isRouter() && session.getOwnerId() != null) {
                var player = level.getServer().getPlayerList().getPlayer(session.getOwnerId());
                if (player == null || !player.level().dimension().equals(level.dimension())) {
                    session.fail("device_unavailable");
                } else if (session.getFrequenciesMask() != 0 || session.hasRadioCaps()) {
                    // The service bounds expensive scans with its reception cache, including handover hysteresis.
                    var snapshot = RadioAccessService.scan(player);
                    var scan = snapshot.payload();
                    if (!scan.found()) session.fail(RadioAccessService.unavailableReason(scan));
                    else session.updateRadioAttachment(scan.antennaPos(), scan.frequenciesMask(), snapshot.downCaps(), snapshot.upCaps());
                }
                if (session.isTerminal()) {
                    sendSessionUpdate(level, session);
                    toRemove.add(session);
                    continue;
                }
            }
            NetworkNode source = nodes.get(session.getSourcePos());
            boolean radio = source != null && source.getType() == NetworkNode.NodeType.ANTENNA
                    && (session.getFrequenciesMask() != 0 || session.hasRadioCaps());
            Map<TelecomFrequency, Integer> downCaps = radio ? radioCeilings(session, source, false) : Map.of();
            Map<TelecomFrequency, Integer> upCaps = radio ? radioCeilings(session, source, true) : Map.of();
            if (radio) {
                int enabledMask = session.getFrequenciesMask() & source.getFrequenciesMask();
                if (session.hasRadioCaps()) session.updateRadioAttachment(source.getPosition(), enabledMask, downCaps, upCaps);
                else session.setFrequenciesMask(enabledMask);
                if (downCaps.isEmpty()) {
                    session.fail("radio_lost");
                    sendSessionUpdate(level, session);
                    toRemove.add(session);
                    continue;
                }
            }
            NetworkNode server = nodes.get(session.getDestPos());
            PathStats stats = server != null && server.getType() == NetworkNode.NodeType.SERVER && nodes.containsKey(session.getSourcePos())
                    ? calculatePathStats(session.getSourcePos(), session.getDestPos(), physical) : null;
            if (stats == null) {
                session.fail("route_lost");
                sendSessionUpdate(level, session);
                toRemove.add(session);
                continue;
            }
            session.setPingMs(stats.pingMs() + session.getExtraPing());
            session.tick();
            
            if (session.getState() == TrafficSession.SessionState.FINISHED) {
                toRemove.add(session);
                sendSessionUpdate(level, session);
                
                // Save results to RouterBlockEntity if applicable
                NetworkNode node = nodes.get(session.getSourcePos());
                if (!session.isPassive() && session.isRouter() && node != null && node.getType() == NetworkNode.NodeType.ROUTER
                        && level.hasChunkAt(session.getSourcePos())) {
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(session.getSourcePos());
                    if (be instanceof com.florentdubut.telecom.block.entity.RouterBlockEntity router) {
                        router.setLastSpeedtestResults(session.getFinalDownBw(), session.getFinalUpBw(), session.getPingMs());
                    }
                }
                continue;
            }
            
            if (session.getState() == TrafficSession.SessionState.DOWNLOAD || session.getState() == TrafficSession.SessionState.UPLOAD) {
                boolean upload = session.getState() == TrafficSession.SessionState.UPLOAD;
                int hardwareMax = upload ? stats.uploadBandwidthMbps() : stats.bandwidthMbps();
                Map<TelecomFrequency, Integer> radioCaps = upload ? upCaps : downCaps;
                int radioTotal = radioCaps.values().stream().mapToInt(Integer::intValue).sum();
                if (radio) hardwareMax = Math.min(hardwareMax, radioTotal);
                int requested = session.getRequestedBandwidth(hardwareMax);
                if (requested == 0) {
                    session.setActualBandwidth(0);
                    continue;
                }
                List<NetworkEdge> path = findShortestPath(session.getSourcePos(), session.getDestPos());
                if (path != null) {
                    java.util.Set<Object> resources = new java.util.LinkedHashSet<>();
                    java.util.Set<BlockPos> sessionNodes = new java.util.LinkedHashSet<>();
                    sessionNodes.add(session.getSourcePos());
                    sessionNodes.add(session.getDestPos());
                    for (NetworkEdge edge : path) {
                        resources.add(edge);
                        resources.addAll(physical.blocks.get(edge));
                        sessionNodes.add(edge.getNodeA());
                        sessionNodes.add(edge.getNodeB());
                    }
                    for (BlockPos pos : sessionNodes) {
                        resources.add(new NodeBudget(pos, upload));
                    }
                    Map<Object, Double> weights = new HashMap<>();
                    // Parallel carriers use a proportional fixed split, not dynamic per-carrier redistribution.
                    // A handset remains ONE max-min flow; UP consumes the same airtime budget as DOWN.
                    for (var band : radioCaps.entrySet()) {
                        RadioBudget budget = new RadioBudget(session.getSourcePos(), band.getKey());
                        resources.add(budget);
                        double share = (double) band.getValue() / radioTotal;
                        int nominalDown = source.getRadioConfig().capacityMbps(band.getKey());
                        int nominalUp = Math.max(1, (int) Math.floor(nominalDown * AntennaRadioConfig.uploadRatio(band.getKey())));
                        // Normalize against rounded integer capacities, including the 2G one-Mbps minimum.
                        weights.put(budget, upload ? share * ((double) nominalDown / nominalUp) : share);
                    }
                    // Count after per-flow deduplication, before retaining any resources or capacities.
                    if (resources.size() > MAX_ALLOCATION_RESOURCE_REFERENCES - retainedResourceReferences) {
                        session.fail("network_limit");
                        sendSessionUpdate(level, session);
                        toRemove.add(session);
                        continue;
                    }
                    retainedResourceReferences += resources.size();
                    for (Object resource : resources) {
                        if (resource instanceof NetworkEdge edge) {
                            capacities.put(edge, edge.getEffectiveBandwidthMbps());
                        } else if (resource instanceof BlockPos pos) {
                            capacities.put(pos, physical.capacities.get(pos));
                        } else if (resource instanceof NodeBudget budget) {
                            NetworkNode node = nodes.get(budget.pos);
                            capacities.put(budget, node == null ? 0 : upload ? node.getCapacityUp() : node.getCapacityDown());
                        } else if (resource instanceof RadioBudget budget) {
                            capacities.put(budget, source.getRadioConfig().capacityMbps(budget.frequency));
                        }
                    }
                    requests.put(session, new BandwidthAllocator.Request(session.getSessionId(), requested, resources, weights));
                }
            }
            
        }
        
        int[] allocations = bandwidthAllocator.allocate(new ArrayList<>(requests.values()), capacities);
        int allocationIndex = 0;
        for (Map.Entry<TrafficSession, BandwidthAllocator.Request> entry : requests.entrySet()) {
            TrafficSession session = entry.getKey();
            int actual = allocations[allocationIndex++];
            boolean upload = session.getState() == TrafficSession.SessionState.UPLOAD;
            session.setActualBandwidth(actual);
            if (upload) totalBandwidthUp += actual;
            else totalBandwidthDown += actual;

            // The exact same deduplicated resources drive both allocation and telemetry.
            for (Object resource : entry.getValue().resources()) {
                if (resource instanceof NetworkEdge edge) {
                    edge.setCurrentUsage(edge.getCurrentUsage() + actual);
                    if (upload) edge.setCurrentUsageUp(edge.getCurrentUsageUp() + actual);
                    else edge.setCurrentUsageDown(edge.getCurrentUsageDown() + actual);
                } else if (resource instanceof BlockPos pos) {
                    (upload ? actualBlockUsageUp : actualBlockUsageDown).merge(pos, actual, Integer::sum);
                } else if (resource instanceof NodeBudget budget) {
                    NetworkNode node = nodes.get(budget.pos);
                    if (node != null) {
                        if (upload) node.setCurrentUsageUp(node.getCurrentUsageUp() + actual);
                        else node.setCurrentUsageDown(node.getCurrentUsageDown() + actual);
                    }
                } else if (resource instanceof RadioBudget budget) {
                    actualRadioUsage.merge(budget, actual * entry.getValue().weight(budget), Double::sum);
                }
            }
        }
        
        activeSessions.removeAll(toRemove);
        for (TrafficSession session : toRemove) bandwidthAllocator.forget(session.getSessionId());
        for (TrafficSession session : activeSessions) {
            if (!session.isPassive() && session.getTicksElapsed() % 2 == 0) {
                sendSessionUpdate(level, session);
            }
        }
        
    }

    private Map<TelecomFrequency, Integer> radioCeilings(TrafficSession session, NetworkNode antenna, boolean upload) {
        Map<TelecomFrequency, Integer> result = new java.util.EnumMap<>(TelecomFrequency.class);
        var reception = upload ? session.getRadioUpCaps() : session.getRadioDownCaps();
        int mask = session.getFrequenciesMask() & antenna.getFrequenciesMask();
        for (TelecomFrequency frequency : TelecomFrequency.values()) {
            if ((mask & (1 << frequency.ordinal())) == 0) continue;
            int nominal = antenna.getRadioConfig().capacityMbps(frequency);
            if (upload) nominal = Math.max(1, (int) Math.floor(nominal * AntennaRadioConfig.uploadRatio(frequency)));
            int cap = session.hasRadioCaps() ? Math.min(nominal, reception.getOrDefault(frequency, 0)) : nominal;
            if (cap > 0) result.put(frequency, cap);
        }
        return result;
    }
    
    public int getTotalBandwidthUp() {
        return totalBandwidthUp;
    }
    
    public int getTotalBandwidthDown() {
        return totalBandwidthDown;
    }
    
    public TrafficSession getSessionByDeviceId(String deviceId) {
        for (TrafficSession session : activeSessions) {
            if (session.getDeviceId().equals(deviceId)) return session;
        }
        return null;
    }

    /** Legacy lookup only: multiple devices can have the same IP. */
    public TrafficSession getSessionByIp(String ip) {
        for (TrafficSession s : activeSessions) {
            if (s.getClientIp() != null && s.getClientIp().equals(ip)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Returns per-frequency utilization stats for a given antenna.
     * Actual usage is granted DOWN-equivalent airtime (UP scaled by nominal DOWN / nominal UP).
     * Aggregate before rounding down for display, so fractional grants cannot overstate capacity.
     */
    public java.util.Map<TelecomFrequency, AntennaFreqStats> getAntennaUtilization(net.minecraft.core.BlockPos antennaPos) {
        java.util.Map<TelecomFrequency, AntennaFreqStats> result = new java.util.LinkedHashMap<>();
        NetworkNode antenna = nodes.get(antennaPos);
        if (antenna == null) return result;
        if (antenna.getType() == NetworkNode.NodeType.ANTENNA) {
            for (TelecomFrequency frequency : TelecomFrequency.values()) {
                if ((antenna.getFrequenciesMask() & (1 << frequency.ordinal())) == 0) continue;
                int max = antenna.getRadioConfig().capacityMbps(frequency);
                double used = actualRadioUsage.getOrDefault(new RadioBudget(antennaPos, frequency), 0.0);
                result.put(frequency, new AntennaFreqStats(Math.min(max, (int) Math.floor(used + 1e-7)), max));
            }
            return result;
        }
        // Legacy non-radio synthetic sessions may carry frequency metadata for display only.
        for (TrafficSession s : activeSessions) {
            if (s.getAntennaPos() != null && s.getAntennaPos().equals(antennaPos) && s.getFrequenciesMask() != 0) {
                // Find how many frequencies are used
                java.util.List<TelecomFrequency> activeFreqs = new java.util.ArrayList<>();
                for (TelecomFrequency freq : TelecomFrequency.values()) {
                    if ((s.getFrequenciesMask() & (1 << freq.ordinal())) != 0) {
                        activeFreqs.add(freq);
                    }
                }
                
                if (!activeFreqs.isEmpty()) {
                    // Distribute actual bandwidth proportionally across used frequencies based on their max speeds
                    int totalMax = 0;
                    for (TelecomFrequency f : activeFreqs) {
                        totalMax += f.getMaxSpeedMb();
                    }
                    if (totalMax > 0) {
                        for (TelecomFrequency freq : activeFreqs) {
                            int maxBw = freq.getMaxSpeedMb();
                            // Convert actualBandwidth * maxBw to long to avoid integer overflow, then divide
                            int bwForFreq = (int)(((long)s.getActualBandwidth() * maxBw) / totalMax);
                            result.merge(freq, new AntennaFreqStats(bwForFreq, maxBw),
                                (a, b) -> new AntennaFreqStats(a.actualMbps() + b.actualMbps(), a.maxMbps()));
                        }
                    }
                }
            }
        }
        return result;
    }

    public record AntennaFreqStats(int actualMbps, int maxMbps) {
        public float utilizationPercent() {
            if (maxMbps <= 0) return 0f;
            return Math.min(1f, (float) actualMbps / maxMbps);
        }
    }

    public NetworkNode getNode(BlockPos pos) {
        return nodes.get(pos);
    }

    public java.util.Collection<NetworkNode> getNodes() {
        return java.util.Collections.unmodifiableCollection(nodes.values());
    }

    public NetworkNode getNodeByIp(String ip) {
        for (NetworkNode node : nodes.values()) {
            if (ip != null && ip.equals(node.getIpAddress())) {
                return node;
            }
        }
        return null;
    }

    public void clearEdges() {
        topologyRevision++;
        synchronized(edges) {
            edges.clear();
        }
        pathCache.clear();
        setDirty();
    }
    
    public void setEdges(java.util.List<com.florentdubut.telecom.network.NetworkEdge> newEdges) {
        topologyRevision++;
        synchronized(edges) {
            edges.clear();
            edges.addAll(newEdges);
        }
        pathCache.clear();
        setDirty();
    }

    /** Replace derived operational links without touching parallel wired connections or retracing cables. */
    public void setMicrowaveEdges(List<NetworkEdge> newEdges) {
        reconcileEdges(newEdges, true);
    }

    /** Cable discovery must not discard the microwave service's current operational links. */
    public void setCableEdges(List<NetworkEdge> newEdges) {
        reconcileEdges(newEdges, false);
    }

    private record EdgeKey(BlockPos a, BlockPos b, NetworkEdge.EdgeType type) {
        static EdgeKey of(NetworkEdge edge) {
            boolean forward = edge.getNodeA().asLong() <= edge.getNodeB().asLong();
            return new EdgeKey(forward ? edge.getNodeA() : edge.getNodeB(),
                    forward ? edge.getNodeB() : edge.getNodeA(), edge.getType());
        }
    }

    private record EdgeDefinition(EdgeKey key,
                                  int nominal, int effective, int length, int latency, List<BlockPos> path) {
        static EdgeDefinition of(NetworkEdge edge) {
            boolean forward = edge.getNodeA().asLong() <= edge.getNodeB().asLong();
            return new EdgeDefinition(EdgeKey.of(edge), edge.getBandwidthMax(),
                    edge.getEffectiveBandwidthMbps(), edge.getLength(), edge.getLatencyMs(),
                    forward ? edge.getPathBlocks() : edge.getPathBlocks().reversed());
        }
    }

    private void reconcileEdges(List<NetworkEdge> newEdges, boolean microwave) {
        // Match undirected definitions, retaining unchanged edge identities (allocator resources and telemetry).
        Map<EdgeDefinition, java.util.ArrayDeque<NetworkEdge>> previous = new HashMap<>();
        int oldCount = 0;
        for (NetworkEdge edge : edges) {
            if ((edge.getType() == NetworkEdge.EdgeType.MICROWAVE) != microwave) continue;
            previous.computeIfAbsent(EdgeDefinition.of(edge), key -> new java.util.ArrayDeque<>()).add(edge);
            oldCount++;
        }
        List<NetworkEdge> replacements = new ArrayList<>(newEdges.size());
        boolean changed = oldCount != newEdges.size();
        for (NetworkEdge edge : newEdges) {
            if ((edge.getType() == NetworkEdge.EdgeType.MICROWAVE) != microwave) {
                throw new IllegalArgumentException("wrong transport type for edge reconciliation");
            }
            var matches = previous.get(EdgeDefinition.of(edge));
            if (matches != null && !matches.isEmpty()) {
                replacements.add(matches.removeFirst());
            } else {
                replacements.add(edge);
                changed = true;
            }
        }
        if (!changed) return;
        Map<EdgeKey, java.util.ArrayDeque<Integer>> replacementIndices = new HashMap<>();
        for (int i = 0; i < replacements.size(); i++) {
            replacementIndices.computeIfAbsent(EdgeKey.of(replacements.get(i)), key -> new java.util.ArrayDeque<>()).add(i);
        }
        boolean[] retained = new boolean[replacements.size()];
        synchronized (edges) {
            // Preserve BFS tie-breaking against parallel transports when only capacity or latency changes.
            var iterator = edges.listIterator();
            while (iterator.hasNext()) {
                NetworkEdge edge = iterator.next();
                if ((edge.getType() == NetworkEdge.EdgeType.MICROWAVE) != microwave) continue;
                var indices = replacementIndices.get(EdgeKey.of(edge));
                if (indices == null || indices.isEmpty()) {
                    iterator.remove();
                } else {
                    int index = indices.removeFirst();
                    iterator.set(replacements.get(index));
                    retained[index] = true;
                }
            }
            for (int i = 0; i < replacements.size(); i++) {
                if (!retained[i]) edges.add(replacements.get(i));
            }
        }
        topologyRevision++;
        pathCache.clear();
        if (!microwave) setDirty();
    }

    public List<NetworkEdge> getEdges() {
        return java.util.Collections.unmodifiableList(edges);
    }

    public int routePacket(Packet packet) {
        NetworkNode source = getNodeByIp(packet.getSourceIp());
        NetworkNode dest = getNodeByIp(packet.getDestIp());
        
        if (source == null || dest == null) return -1; // Unreachable
        if (physicalNetwork() == null) return -1;
        
        List<NetworkEdge> path = findShortestPath(source.getPosition(), dest.getPosition());
        if (path == null) return -1; // No path found
        
        long latency = 0;
        for (NetworkEdge edge : path) {
            // Simulated latency calculation
            long edgeLatency = edge.getType() == NetworkEdge.EdgeType.MICROWAVE
                    ? (edge.getLatencyMs() + 49L) / 50L
                    : edge.getLength() / 10; // Legacy wire delay: 1 tick per 10 blocks
            
            // Saturation penalty
            int capacity = edge.getEffectiveBandwidthMbps();
            if (capacity == 0) return -1;
            double saturation = ((long) edge.getCurrentUsage() + Math.max(0, packet.getSize())) / (double) capacity;
            if (saturation > 1.0f) {
                edgeLatency += (long)((saturation - 1.0) * 100); // Massive delay if saturated
            }
            
            latency += edgeLatency;
            
            // In a real tick simulation, we'd add size to currentUsage and clear it every tick.
            // For now, just a basic simulation concept.
        }
        
        return (int) Math.min(Integer.MAX_VALUE, latency); // Returns ticks to wait for packet arrival
    }

    private transient final Map<String, List<NetworkEdge>> pathCache = new java.util.LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<NetworkEdge>> eldest) {
            return size() > 1024;
        }
    };
    
    private List<NetworkEdge> findShortestPath(BlockPos start, BlockPos end) {
        if (start.equals(end)) return new ArrayList<>();
        
        String cacheKey = start.asLong() + "-" + end.asLong();
        if (pathCache.containsKey(cacheKey)) {
            return pathCache.get(cacheKey);
        }
        
        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, NetworkEdge> edgeToParent = new HashMap<>();
        
        queue.add(start);
        boolean found = false;
        
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            
            if (current.equals(end)) {
                found = true;
                break;
            }
            
            for (NetworkEdge edge : edges) {
                BlockPos neighbor = null;
                if (edge.getNodeA().equals(current)) neighbor = edge.getNodeB();
                else if (edge.getNodeB().equals(current)) neighbor = edge.getNodeA();
                
                if (neighbor != null && !parent.containsKey(neighbor) && !neighbor.equals(start)) {
                    parent.put(neighbor, current);
                    edgeToParent.put(neighbor, edge);
                    queue.add(neighbor);
                }
            }
        }
        
        if (!found) return null;
        
        List<NetworkEdge> path = new ArrayList<>();
        BlockPos current = end;
        while (!current.equals(start)) {
            NetworkEdge edge = edgeToParent.get(current);
            path.add(0, edge);
            current = parent.get(current);
        }
        
        pathCache.put(cacheKey, path);
        return path;
    }

    public record PathStats(int pingMs, int bandwidthMbps, int uploadBandwidthMbps) {
        public PathStats(int pingMs, int bandwidthMbps) {
            this(pingMs, bandwidthMbps, bandwidthMbps);
        }
    }

    public PathStats calculatePathStats(BlockPos sourcePos, BlockPos destPos) {
        return calculatePathStats(sourcePos, destPos, physicalNetwork());
    }

    private PathStats calculatePathStats(BlockPos sourcePos, BlockPos destPos, PhysicalNetwork physical) {
        if (physical == null || !nodes.containsKey(sourcePos) || !nodes.containsKey(destPos)) return null;
        List<NetworkEdge> path = findShortestPath(sourcePos, destPos);
        if (path == null) return null;

        PathMetrics metrics = initialMetrics(sourcePos);
        BlockPos current = sourcePos;
        for (NetworkEdge edge : path) {
            current = edge.getNodeA().equals(current) ? edge.getNodeB() : edge.getNodeA();
            metrics = metrics.append(edge, nodes.get(current), physical.capacity(edge));
        }
        return metrics.stats();
    }

    private PathMetrics initialMetrics(BlockPos sourcePos) {
        NetworkNode source = nodes.get(sourcePos);
        return new PathMetrics(0, source.getCapacityDown(), source.getCapacityUp());
    }

    private record PathMetrics(float totalPing, int minBandwidth, int minUploadBandwidth) {
        PathMetrics append(NetworkEdge edge, NetworkNode node, int effectiveCapacity) {
            float delayPerBlock = switch (edge.getType()) {
                case FIBER -> 0.05f;
                case MEDIUM_FIBER -> 0.02f;
                case BIG_FIBER -> 0.01f;
                case COPPER -> 0.2f;
                case MICROWAVE -> 0.0f;
            };
            float delay = edge.getType() == NetworkEdge.EdgeType.MICROWAVE
                    ? edge.getLatencyMs() : edge.getLength() * delayPerBlock;
            return new PathMetrics(totalPing + delay,
                    Math.min(Math.min(minBandwidth, effectiveCapacity), node == null ? 0 : node.getCapacityDown()),
                    Math.min(Math.min(minUploadBandwidth, effectiveCapacity), node == null ? 0 : node.getCapacityUp()));
        }

        PathStats stats() {
            return new PathStats(Math.max(1, (int)(totalPing + 1.0f)), minBandwidth, minUploadBandwidth);
        }
    }
}
