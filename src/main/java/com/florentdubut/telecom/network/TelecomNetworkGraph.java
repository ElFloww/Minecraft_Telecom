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
            if (nodeTag.contains("CapDown")) {
                node.setCapacityDown(nodeTag.getIntOr("CapDown", 1000));
            }
            if (nodeTag.contains("CapUp")) {
                node.setCapacityUp(nodeTag.getIntOr("CapUp", 1000));
            }
            graph.nodes.put(pos, node);
        }

        ListTag edgesTag = tag.getListOrEmpty("Edges");
        for (int i = 0; i < edgesTag.size(); i++) {
            CompoundTag edgeTag = edgesTag.getCompound(i).orElseThrow(() -> new IllegalArgumentException("invalid edge"));
            BlockPos nodeA = edgeTag.read("NodeA", BlockPos.CODEC).orElseThrow(() -> new IllegalArgumentException("invalid edge source"));
            BlockPos nodeB = edgeTag.read("NodeB", BlockPos.CODEC).orElseThrow(() -> new IllegalArgumentException("invalid edge target"));
            int bandwidthMax = edgeTag.getIntOr("BandwidthMax", 0);
            int length = edgeTag.getIntOr("Length", 0);
            NetworkEdge.EdgeType type = NetworkEdge.EdgeType.valueOf(edgeTag.getStringOr("Type", ""));
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
        return graph;
    }

    private CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("SchemaVersion", 1);
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
            nodeTag.putInt("CapDown", node.getCapacityDown());
            nodeTag.putInt("CapUp", node.getCapacityUp());
            nodesTag.add(nodeTag);
        }
        tag.put("Nodes", nodesTag);

        ListTag edgesTag = new ListTag();
        for (NetworkEdge edge : edges) {
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

    private boolean needsRecalculation = false;
    private int delayedRecalculationTimer = -1;

    public void markForRecalculation() {
        this.needsRecalculation = true;
    }

    public void scheduleDelayedRecalculation(int ticks) {
        if (this.delayedRecalculationTimer < 0 || this.delayedRecalculationTimer > ticks) {
            this.delayedRecalculationTimer = ticks;
        }
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
        int sum = 0;
        for (NetworkEdge edge : edges) {
            if (edge.getPathBlocks() != null && edge.getPathBlocks().contains(pos)) {
                sum += edge.getCurrentUsageDown();
            }
        }
        return sum;
    }
    public int getActualBlockUsageUp(BlockPos pos) {
        int sum = 0;
        for (NetworkEdge edge : edges) {
            if (edge.getPathBlocks() != null && edge.getPathBlocks().contains(pos)) {
                sum += edge.getCurrentUsageUp();
            }
        }
        return sum;
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

    public void startSpeedtest(BlockPos sourcePos, String clientIp, int targetDownBw, int targetUpBw, int extraPing, int frequenciesMask, int durationTicks, boolean isPassive, @org.jetbrains.annotations.Nullable net.minecraft.server.level.ServerPlayer player) {
        NetworkNode source = nodes.get(sourcePos);
        String deviceId = source != null && source.getType() == NetworkNode.NodeType.ROUTER
                ? TrafficSession.routerDeviceId(sourcePos)
                : player != null ? TrafficSession.mobileDeviceId(player.getUUID()) : "mobile-ip:" + clientIp;
        if (!nodes.containsKey(sourcePos) || clientIp == null || clientIp.isBlank() || clientIp.length() > 45
                || targetDownBw < 1 || targetDownBw > 1_000_000 || targetUpBw < 1 || targetUpBw > 1_000_000
                || extraPing < 0 || extraPing > 60_000 || durationTicks < 1 || durationTicks > 12_000
                || (frequenciesMask & ~((1 << TelecomFrequency.values().length) - 1)) != 0) {
            rejectSpeedtest(player, isPassive, clientIp, deviceId, "Speedtest rejected: invalid request.");
            return;
        }
        TrafficSession existing = getSessionByDeviceId(deviceId);
        if (existing != null && (isPassive || !existing.isPassive())) {
            rejectSpeedtest(player, isPassive, clientIp, deviceId, "A speedtest is already running on this device.");
            return;
        }
        if (existing == null && activeSessions.size() >= 256) {
            rejectSpeedtest(player, isPassive, clientIp, deviceId, "Speedtest rejected: session limit reached.");
            return;
        }
        
        // Find the best server to connect to
        NetworkNode bestServer = null;
        PathStats bestStats = null;
        
        for (NetworkNode node : nodes.values()) {
            if (node.getType() == NetworkNode.NodeType.SERVER) {
                PathStats stats = calculatePathStats(sourcePos, node.getPosition());
                if (stats != null) {
                    if (bestStats == null || stats.pingMs() < bestStats.pingMs()) {
                        bestStats = stats;
                        bestServer = node;
                    }
                }
            }
        }
        
        if (bestServer != null && bestStats != null) {
            if (existing != null) activeSessions.remove(existing);
            TrafficSession session = new TrafficSession(sourcePos, bestServer.getPosition(), clientIp, targetDownBw, targetUpBw, durationTicks, isPassive, deviceId);
            if (player != null) session.setOwnerId(player.getUUID());
            session.setExtraPing(extraPing);
            session.setPingMs(bestStats.pingMs() + extraPing);
            session.setAntennaPos(sourcePos); // Used by mobile sessions to map back to antenna
            session.setFrequenciesMask(frequenciesMask);
            activeSessions.add(session);
        } else {
            rejectSpeedtest(player, isPassive, clientIp, deviceId, "Failed to start Speedtest: No complete path to a Server or NRO was found.");
        }
    }

    private void rejectSpeedtest(net.minecraft.server.level.ServerPlayer player, boolean passive, String ip, String deviceId, String message) {
        if (player == null || passive) return;
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new SpeedtestUpdatePayload(
                ip == null ? "" : ip.substring(0, Math.min(ip.length(), 45)), "REJECTED", 0, 0, 0, 0,
                player.level().dimension().identifier().toString(), deviceId.substring(0, Math.min(deviceId.length(), 128)),
                new java.util.UUID(0, 0), 0, 0));
    }

    private void sendSessionUpdate(ServerLevel level, TrafficSession session) {
        SpeedtestUpdatePayload update = new SpeedtestUpdatePayload(
                session.getClientIp(), session.getState().name(), session.getPingMs(),
                session.isTerminal() ? 0 : session.getActualBandwidth(), session.getTicksElapsed(), session.getTotalTicksPerPhase(),
                level.dimension().identifier().toString(), session.getDeviceId(), session.getSessionId(),
                session.getFinalDownBw(), session.getFinalUpBw());
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
                // Find nearest antenna for this player, tracking best signal AND best frequency
                com.florentdubut.telecom.block.entity.AntennaBlockEntity bestAntenna = null;
                TelecomFrequency bestFreq = null;
                float bestSignal = -1000f;
                String bestIp = null;

                for (NetworkNode node : nodes.values()) {
                    if (node.getType() == NetworkNode.NodeType.ANTENNA) {
                        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(node.getPosition());
                        if (be instanceof com.florentdubut.telecom.block.entity.AntennaBlockEntity antenna) {
                            for (TelecomFrequency freq : TelecomFrequency.values()) {
                                if (antenna.isFrequencyEnabled(freq)) {
                                    float signal = com.florentdubut.telecom.network.SignalPropagator.calculateSignal(level, antenna.getBlockPos(), player.blockPosition().above(), freq).powerDbm;
                                    if (signal > -120f && signal > bestSignal) {
                                        bestSignal = signal;
                                        bestAntenna = antenna;
                                        bestFreq = freq;
                                        bestIp = getMobileIp(player.getUUID());
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (bestAntenna != null && bestFreq != null) {
                    final String finalBestIp = bestIp;
                    boolean hasSession = getSessionByDeviceId(TrafficSession.mobileDeviceId(player.getUUID())) != null;
                    if (!hasSession) {
                        int randDown = 1 + (int)(Math.random() * 20);
                        int randUp = 1 + (int)(Math.random() * 5);
                        int extraPing = 20 + (int)(Math.random() * 50);
                        startSpeedtest(bestAntenna.getBlockPos(), finalBestIp, randDown, randUp, extraPing, (1 << bestFreq.ordinal()), 100, true, player);
                    }
                }
            }
        }
    }

    public void tickTraffic(ServerLevel level) {
        tickPassiveTraffic(level);

        if (delayedRecalculationTimer > 0) {
            delayedRecalculationTimer--;
        } else if (delayedRecalculationTimer == 0) {
            delayedRecalculationTimer = -1;
            needsRecalculation = true;
        }

        if (needsRecalculation) {
            needsRecalculation = false;
            NetworkTracer.recalculateNetwork(level);
            return; // Skip this tick, it will resume next tick
        }

        // Reset current usage
        for (NetworkEdge edge : edges) {
            edge.setCurrentUsage(0);
            edge.setCurrentUsageDown(0);
            edge.setCurrentUsageUp(0);
        }
        
        totalBandwidthUp = 0;
        totalBandwidthDown = 0;
        
        List<TrafficSession> toRemove = new ArrayList<>();
        Map<TrafficSession, List<NetworkEdge>> sessionPaths = new HashMap<>();
        Map<TrafficSession, Integer> sessionRequested = new HashMap<>();
        Map<BlockPos, Integer> blockUsage = new HashMap<>();
        Map<BlockPos, Integer> blockCapacity = new HashMap<>();

        for (TrafficSession session : activeSessions) {
            if (!session.isRouter() && session.getOwnerId() != null
                    && level.getServer().getPlayerList().getPlayer(session.getOwnerId()) == null) {
                session.fail();
                sendSessionUpdate(level, session);
                toRemove.add(session);
                continue;
            }
            PathStats stats = calculatePathStats(session.getSourcePos(), session.getDestPos());
            if (stats == null) {
                session.fail();
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
                if (session.isRouter() && node != null && node.getType() == NetworkNode.NodeType.ROUTER
                        && level.hasChunkAt(session.getSourcePos())) {
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(session.getSourcePos());
                    if (be instanceof com.florentdubut.telecom.block.entity.RouterBlockEntity router) {
                        router.setLastSpeedtestResults(session.getFinalDownBw(), session.getFinalUpBw(), session.getPingMs());
                    }
                }
                continue;
            }
            
            if (session.getState() == TrafficSession.SessionState.DOWNLOAD || session.getState() == TrafficSession.SessionState.UPLOAD) {
                int hardwareMax = stats.bandwidthMbps();
                int requested = Math.min(session.getState() == TrafficSession.SessionState.DOWNLOAD ? session.getTargetDownBw() : session.getTargetUpBw(), hardwareMax);
                List<NetworkEdge> path = findShortestPath(session.getSourcePos(), session.getDestPos());
                if (path != null) {
                    sessionPaths.put(session, path);
                    sessionRequested.put(session, requested);
                    // Accumulate requested usage to compute congestion per physical block
                    for (NetworkEdge edge : path) {
                        edge.setCurrentUsage(edge.getCurrentUsage() + requested);
                        for (BlockPos pos : edge.getPathBlocks()) {
                            blockUsage.put(pos, blockUsage.getOrDefault(pos, 0) + requested);
                            blockCapacity.merge(pos, edge.getBandwidthMax(), Math::min);
                        }
                    }
                }
            }
            
        }
        
        // Phase 2: Compute actual bandwidth considering congestion per physical block
        for (Map.Entry<TrafficSession, Integer> entry : sessionRequested.entrySet()) {
            TrafficSession session = entry.getKey();
            int requested = entry.getValue();
            List<NetworkEdge> path = sessionPaths.get(session);
            
            float minRatio = 1.0f;
            for (NetworkEdge edge : path) {
                // Logical links still share capacity when no physical path blocks were recorded.
                if (edge.getCurrentUsage() > edge.getBandwidthMax()) {
                    minRatio = Math.min(minRatio, (float) edge.getBandwidthMax() / edge.getCurrentUsage());
                }
                for (BlockPos pos : edge.getPathBlocks()) {
                    int usage = blockUsage.getOrDefault(pos, 0);
                    int cap = blockCapacity.getOrDefault(pos, edge.getBandwidthMax());
                    if (usage > cap) {
                        float ratio = (float) cap / usage;
                        if (ratio < minRatio) {
                            minRatio = ratio;
                        }
                    }
                }
            }
            
            int actual = (int)(requested * minRatio);
            // Realistic oscillation (92% to 100%)
            actual = (int)(actual * (0.92f + Math.random() * 0.08f));
            
            session.setActualBandwidth(actual);
            
            if (session.getState() == TrafficSession.SessionState.DOWNLOAD) {
                totalBandwidthDown += actual;
            } else if (session.getState() == TrafficSession.SessionState.UPLOAD) {
                totalBandwidthUp += actual;
            }
        }
        
        // Reset node usage
        for (NetworkNode node : nodes.values()) {
            node.setCurrentUsageDown(0);
            node.setCurrentUsageUp(0);
        }

        for (Map.Entry<TrafficSession, Integer> entry : sessionRequested.entrySet()) {
            TrafficSession session = entry.getKey();
            int actual = session.getActualBandwidth();
            List<NetworkEdge> path = sessionPaths.get(session);
            
            // Apply to nodes
            java.util.Set<BlockPos> sessionNodes = new java.util.HashSet<>();
            sessionNodes.add(session.getSourcePos());
            sessionNodes.add(session.getDestPos());
            for (NetworkEdge edge : path) {
                sessionNodes.add(edge.getNodeA());
                sessionNodes.add(edge.getNodeB());
            }

            for (BlockPos pos : sessionNodes) {
                NetworkNode node = nodes.get(pos);
                if (node != null) {
                    if (session.getState() == TrafficSession.SessionState.DOWNLOAD) {
                        node.setCurrentUsageDown(node.getCurrentUsageDown() + actual);
                    } else if (session.getState() == TrafficSession.SessionState.UPLOAD) {
                        node.setCurrentUsageUp(node.getCurrentUsageUp() + actual);
                    }
                }
            }
            
            // Apply to edges
            for(NetworkEdge edge : path) {
                if (session.getState() == TrafficSession.SessionState.DOWNLOAD) {
                    edge.setCurrentUsageDown(edge.getCurrentUsageDown() + actual);
                } else {
                    edge.setCurrentUsageUp(edge.getCurrentUsageUp() + actual);
                }
            }
        }
        
        activeSessions.removeAll(toRemove);
        for (TrafficSession session : activeSessions) {
            if (!session.isPassive() && session.getTicksElapsed() % 2 == 0) {
                sendSessionUpdate(level, session);
            }
        }
        
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
     * For each frequency, returns a record with: actual Mbps used and max Mbps capacity.
     */
    public java.util.Map<TelecomFrequency, AntennaFreqStats> getAntennaUtilization(net.minecraft.core.BlockPos antennaPos) {
        java.util.Map<TelecomFrequency, AntennaFreqStats> result = new java.util.LinkedHashMap<>();
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
        return nodes.values();
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

    public List<NetworkEdge> getEdges() {
        return edges;
    }

    public int routePacket(Packet packet) {
        NetworkNode source = getNodeByIp(packet.getSourceIp());
        NetworkNode dest = getNodeByIp(packet.getDestIp());
        
        if (source == null || dest == null) return -1; // Unreachable
        
        List<NetworkEdge> path = findShortestPath(source.getPosition(), dest.getPosition());
        if (path == null) return -1; // No path found
        
        int latency = 0;
        for (NetworkEdge edge : path) {
            // Simulated latency calculation
            int edgeLatency = edge.getLength() / 10; // e.g. 1 tick per 10 blocks
            
            // Saturation penalty
            float saturation = (float)(edge.getCurrentUsage() + packet.getSize()) / edge.getBandwidthMax();
            if (saturation > 1.0f) {
                edgeLatency += (int)((saturation - 1.0f) * 100); // Massive delay if saturated
            }
            
            latency += edgeLatency;
            
            // In a real tick simulation, we'd add size to currentUsage and clear it every tick.
            // For now, just a basic simulation concept.
        }
        
        return latency; // Returns ticks to wait for packet arrival
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

    public record PathStats(int pingMs, int bandwidthMbps) {}

    public PathStats calculatePathStats(BlockPos sourcePos, BlockPos destPos) {
        List<NetworkEdge> path = findShortestPath(sourcePos, destPos);
        if (path == null) return null;
        
        float totalPing = 0;
        int minBandwidth = Integer.MAX_VALUE;
        int distanceCu = 0;
        
        for (NetworkEdge edge : path) {
            int length = edge.getLength();
            if (edge.getType() == NetworkEdge.EdgeType.FIBER) {
                totalPing += length * 0.05f;
                minBandwidth = Math.min(minBandwidth, 10000); // 10 Gbps stable
            } else if (edge.getType() == NetworkEdge.EdgeType.MEDIUM_FIBER) {
                totalPing += length * 0.02f;
                minBandwidth = Math.min(minBandwidth, 100000); // 100 Gbps
            } else if (edge.getType() == NetworkEdge.EdgeType.BIG_FIBER) {
                totalPing += length * 0.01f;
                minBandwidth = Math.min(minBandwidth, 1000000); // 1000 Gbps
            } else if (edge.getType() == NetworkEdge.EdgeType.COPPER) {
                totalPing += length * 0.2f;
                distanceCu += length;
                // Copper max is 1000 Mbps, but drops by 2 Mbps per block of copper in the path
                int currentCuBw = Math.max(10, 1000 - (distanceCu * 2));
                minBandwidth = Math.min(minBandwidth, currentCuBw);
            }
        }
        
        // Base latency
        totalPing += 1.0f; 
        
        if (minBandwidth == Integer.MAX_VALUE) minBandwidth = 0;
        
        return new PathStats(Math.max(1, (int)totalPing), minBandwidth);
    }
}
