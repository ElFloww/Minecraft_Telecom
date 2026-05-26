package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public class TelecomNetworkGraph extends SavedData {
    private static final String DATA_NAME = "telecom_network";

    private final Map<BlockPos, NetworkNode> nodes = new HashMap<>();
    private final List<NetworkEdge> edges = new ArrayList<>();

    public static SavedData.Factory<TelecomNetworkGraph> factory() {
        return new SavedData.Factory<>(
                TelecomNetworkGraph::new,
                TelecomNetworkGraph::load,
                null
        );
    }

    public TelecomNetworkGraph() {}

    public static TelecomNetworkGraph load(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        
        ListTag nodesTag = tag.getList("Nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nodesTag.size(); i++) {
            CompoundTag nodeTag = nodesTag.getCompound(i);
            BlockPos pos = NbtUtils.readBlockPos(nodeTag, "Pos").orElse(BlockPos.ZERO);
            NetworkNode.NodeType type = NetworkNode.NodeType.valueOf(nodeTag.getString("Type"));
            NetworkNode node = new NetworkNode(pos, type);
            if (nodeTag.contains("IP")) {
                node.setIpAddress(nodeTag.getString("IP"));
            }
            if (nodeTag.contains("CIDR")) {
                node.setNetworkCidr(nodeTag.getString("CIDR"));
            }
            graph.nodes.put(pos, node);
        }

        ListTag edgesTag = tag.getList("Edges", Tag.TAG_COMPOUND);
        for (int i = 0; i < edgesTag.size(); i++) {
            CompoundTag edgeTag = edgesTag.getCompound(i);
            BlockPos nodeA = NbtUtils.readBlockPos(edgeTag, "NodeA").orElse(BlockPos.ZERO);
            BlockPos nodeB = NbtUtils.readBlockPos(edgeTag, "NodeB").orElse(BlockPos.ZERO);
            int bandwidthMax = edgeTag.getInt("BandwidthMax");
            int length = edgeTag.getInt("Length");
            NetworkEdge.EdgeType type = NetworkEdge.EdgeType.valueOf(edgeTag.getString("Type"));
            java.util.List<BlockPos> pathBlocks = new java.util.ArrayList<>();
            if (edgeTag.contains("PathBlocks")) {
                long[] blocks = edgeTag.getLongArray("PathBlocks");
                for (long l : blocks) {
                    pathBlocks.add(BlockPos.of(l));
                }
            }
            NetworkEdge edge = new NetworkEdge(nodeA, nodeB, bandwidthMax, length, type, pathBlocks);
            graph.edges.add(edge);
        }

        return graph;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        ListTag nodesTag = new ListTag();
        for (NetworkNode node : nodes.values()) {
            CompoundTag nodeTag = new CompoundTag();
            nodeTag.put("Pos", NbtUtils.writeBlockPos(node.getPosition()));
            nodeTag.putString("Type", node.getType().name());
            if (node.getIpAddress() != null) {
                nodeTag.putString("IP", node.getIpAddress());
            }
            if (node.getNetworkCidr() != null) {
                nodeTag.putString("CIDR", node.getNetworkCidr());
            }
            nodesTag.add(nodeTag);
        }
        tag.put("Nodes", nodesTag);

        ListTag edgesTag = new ListTag();
        for (NetworkEdge edge : edges) {
            CompoundTag edgeTag = new CompoundTag();
            edgeTag.put("NodeA", NbtUtils.writeBlockPos(edge.getNodeA()));
            edgeTag.put("NodeB", NbtUtils.writeBlockPos(edge.getNodeB()));
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

        return tag;
    }

    public static TelecomNetworkGraph get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(factory(), DATA_NAME);
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

    public void addNode(NetworkNode node) {
        nodes.put(node.getPosition(), node);
        pathCache.clear();
        setDirty();
    }

    public void removeNode(BlockPos pos) {
        nodes.remove(pos);
        edges.removeIf(edge -> edge.getNodeA().equals(pos) || edge.getNodeB().equals(pos));
        pathCache.clear();
        setDirty();
    }

    public void addEdge(NetworkEdge edge) {
        edges.add(edge);
        pathCache.clear();
        setDirty();
    }

    public void removeEdgeBetween(BlockPos a, BlockPos b) {
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
    private int totalBandwidthUp = 0;
    private int totalBandwidthDown = 0;

    public void startSpeedtest(BlockPos sourcePos, String clientIp, int targetDownBw, int targetUpBw, int extraPing, int frequenciesMask, int durationTicks, boolean isPassive, @org.jetbrains.annotations.Nullable net.minecraft.server.level.ServerPlayer player) {
        TrafficSession existing = getSessionByIp(clientIp);
        if (existing != null) {
            if (!isPassive && existing.isPassive()) {
                activeSessions.remove(existing); // Kill passive session to prioritize manual test
            } else if (!isPassive) {
                if (player != null) player.sendSystemMessage(net.minecraft.network.chat.Component.literal("A speedtest is already running on this IP."));
                return;
            } else {
                return; // Prevent multiple speedtests
            }
        }
        
        // Find the best server to connect to
        NetworkNode bestServer = null;
        PathStats bestStats = null;
        
        for (NetworkNode node : nodes.values()) {
            if (node.getType() == NetworkNode.NodeType.SERVER || node.getType() == NetworkNode.NodeType.NRO) {
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
            TrafficSession session = new TrafficSession(sourcePos, bestServer.getPosition(), clientIp, targetDownBw, targetUpBw, durationTicks, isPassive);
            session.setExtraPing(extraPing);
            session.setPingMs(bestStats.pingMs());
            session.setAntennaPos(sourcePos); // Used by mobile sessions to map back to antenna
            session.setFrequenciesMask(frequenciesMask);
            activeSessions.add(session);
        } else if (!isPassive && player != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Failed to start Speedtest: No complete path to a Server or NRO was found."));
        }
        setDirty();
    }

    private void tickPassiveTraffic(ServerLevel level) {
        // Routers
        if (!nodes.isEmpty()) {
            for (NetworkNode node : nodes.values()) {
                if (node.getType() == NetworkNode.NodeType.ROUTER) {
                    // Reduced probability to 0.5% (was 1%)
                    if (Math.random() < 0.005) {
                        boolean hasSession = false;
                        for (TrafficSession s : activeSessions) {
                            if (s.getSourcePos().equals(node.getPosition())) {
                                hasSession = true;
                                break;
                            }
                        }
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
                                        bestIp = "10.0." + (antenna.getBlockPos().getX() % 255) + "." + (player.getId() % 255);
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (bestAntenna != null && bestFreq != null) {
                    final String finalBestIp = bestIp;
                    boolean hasSession = activeSessions.stream().anyMatch(s -> finalBestIp.equals(s.getClientIp()));
                    if (!hasSession) {
                        int randDown = 1 + (int)(Math.random() * 20);
                        int randUp = 1 + (int)(Math.random() * 5);
                        int extraPing = 20 + (int)(Math.random() * 50);
                        startSpeedtest(bestAntenna.getBlockPos(), finalBestIp, randDown, randUp, extraPing, (1 << bestFreq.ordinal()), 100, true, null);
                        // Tag the newly created session with antenna and frequency
                        TrafficSession newSession = getSessionByIp(finalBestIp);
                        if (newSession != null) {
                            newSession.setAntennaPos(bestAntenna.getBlockPos());
                            newSession.setFrequenciesMask(1 << bestFreq.ordinal());
                        }
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
            session.tick();
            
            if (session.getState() == TrafficSession.SessionState.FINISHED) {
                toRemove.add(session);
                // Broadcast finished state
                if (!session.isPassive()) {
                    com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload update = new com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload(
                        session.getClientIp(), "FINISHED", session.getPingMs(), 0, session.getTicksElapsed(), session.getTotalTicksPerPhase());
                    net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(update);
                }
                
                // Save results to RouterBlockEntity if applicable
                NetworkNode node = getNodeByIp(session.getClientIp());
                if (node != null && node.getType() == NetworkNode.NodeType.ROUTER) {
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(node.getPosition());
                    if (be instanceof com.florentdubut.telecom.block.entity.RouterBlockEntity router) {
                        router.setLastSpeedtestResults(session.getFinalDownBw(), session.getFinalUpBw(), session.getPingMs());
                    }
                }
                continue;
            }
            
            PathStats stats = calculatePathStats(session.getSourcePos(), session.getDestPos());
            if (stats == null) {
                toRemove.add(session); // Path broken
                continue;
            }
            
            session.setPingMs(stats.pingMs() + session.getExtraPing());
            
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
                            blockCapacity.put(pos, edge.getBandwidthMax());
                        }
                    }
                }
            }
            
            // Broadcast state
            if (!session.isPassive() && session.getTicksElapsed() % 2 == 0) { // Every 2 ticks to reduce spam
                int displayPing = session.getPingMs();
                if (session.getState() != TrafficSession.SessionState.FINISHED) {
                    displayPing += (int)(Math.random() * 5) - 2; // -2 to +2 ms fluctuation
                    if (displayPing < 1) displayPing = 1;
                }
                
                com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload update = new com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload(
                    session.getClientIp(), session.getState().name(), displayPing, session.getActualBandwidth(), session.getTicksElapsed(), session.getTotalTicksPerPhase());
                net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(update);
            }
        }
        
        // Phase 2: Compute actual bandwidth considering congestion per physical block
        for (Map.Entry<TrafficSession, Integer> entry : sessionRequested.entrySet()) {
            TrafficSession session = entry.getKey();
            int requested = entry.getValue();
            List<NetworkEdge> path = sessionPaths.get(session);
            
            float minRatio = 1.0f;
            for (NetworkEdge edge : path) {
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
        
    }
    
    public int getTotalBandwidthUp() {
        return totalBandwidthUp;
    }
    
    public int getTotalBandwidthDown() {
        return totalBandwidthDown;
    }
    
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
        edges.clear();
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

    private transient final Map<String, List<NetworkEdge>> pathCache = new HashMap<>();
    
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
