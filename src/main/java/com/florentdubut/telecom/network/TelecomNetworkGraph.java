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

    public void addNode(NetworkNode node) {
        nodes.put(node.getPosition(), node);
        setDirty();
    }

    public void removeNode(BlockPos pos) {
        nodes.remove(pos);
        edges.removeIf(edge -> edge.getNodeA().equals(pos) || edge.getNodeB().equals(pos));
        setDirty();
    }

    public void addEdge(NetworkEdge edge) {
        edges.add(edge);
        setDirty();
    }

    public void removeEdgeBetween(BlockPos a, BlockPos b) {
        edges.removeIf(edge -> (edge.getNodeA().equals(a) && edge.getNodeB().equals(b)) ||
                               (edge.getNodeA().equals(b) && edge.getNodeB().equals(a)));
        setDirty();
    }

    private final Map<BlockPos, Integer> actualBlockUsageDown = new HashMap<>();
    private final Map<BlockPos, Integer> actualBlockUsageUp = new HashMap<>();

    public int getActualBlockUsageDown(BlockPos pos) {
        return actualBlockUsageDown.getOrDefault(pos, 0);
    }
    public int getActualBlockUsageUp(BlockPos pos) {
        return actualBlockUsageUp.getOrDefault(pos, 0);
    }

    private final List<TrafficSession> activeSessions = new ArrayList<>();
    private int totalBandwidthUp = 0;
    private int totalBandwidthDown = 0;

    public void startSpeedtest(BlockPos sourcePos, String clientIp, int targetDownBw, int targetUpBw, int extraPing, boolean isPassive) {
        if (!isPassive && getSessionByIp(clientIp) != null) return; // Prevent multiple speedtests from the same client
        
        // Find a server to connect to
        NetworkNode serverNode = null;
        for (NetworkNode node : nodes.values()) {
            if (node.getType() == NetworkNode.NodeType.SERVER) {
                serverNode = node;
                break;
            }
        }
        
        if (serverNode != null) {
            PathStats stats = calculatePathStats(sourcePos, serverNode.getPosition());
            if (stats != null) {
                TrafficSession session = new TrafficSession(sourcePos, serverNode.getPosition(), clientIp, targetDownBw, targetUpBw, 100, isPassive); // 100 ticks = 5 seconds per phase
                session.setExtraPing(extraPing);
                session.setPingMs(stats.pingMs());
                activeSessions.add(session);
                setDirty();
            }
        }
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
                            startSpeedtest(node.getPosition(), node.getIpAddress() != null ? node.getIpAddress() : "0.0.0.0", randDown, randUp, 0, true);
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
                // Find nearest antenna for this player
                com.florentdubut.telecom.block.entity.AntennaBlockEntity bestAntenna = null;
                float bestSignal = -1000f;
                String bestIp = null;
                for (NetworkNode node : nodes.values()) {
                    if (node.getType() == NetworkNode.NodeType.ANTENNA) {
                        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(node.getPosition());
                        if (be instanceof com.florentdubut.telecom.block.entity.AntennaBlockEntity antenna) {
                            for (com.florentdubut.telecom.network.TelecomFrequency freq : com.florentdubut.telecom.network.TelecomFrequency.values()) {
                                if (antenna.isFrequencyEnabled(freq)) {
                                    float signal = com.florentdubut.telecom.network.SignalPropagator.calculateSignal(level, antenna.getBlockPos(), player.blockPosition().above(), freq).powerDbm;
                                    if (signal > -120f && signal > bestSignal) {
                                        bestSignal = signal;
                                        bestAntenna = antenna;
                                        bestIp = "10.0." + (antenna.getBlockPos().getX() % 255) + "." + (player.getId() % 255);
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (bestAntenna != null) {
                    boolean hasSession = false;
                    for (TrafficSession s : activeSessions) {
                        if (s.getClientIp() != null && s.getClientIp().equals(bestIp)) {
                            hasSession = true;
                            break;
                        }
                    }
                    if (!hasSession) {
                        // Very low consumption for phones (e.g., messaging, small loading)
                        int randDown = 1 + (int)(Math.random() * 20); // 1 to 20 Mbps (was 1-500)
                        int randUp = 1 + (int)(Math.random() * 5); // 1 to 5 Mbps (was 1-100)
                        int extraPing = 20 + (int)(Math.random() * 50);
                        startSpeedtest(bestAntenna.getBlockPos(), bestIp, randDown, randUp, extraPing, true);
                    }
                }
            }
        }
    }

    public void tickTraffic(ServerLevel level) {
        tickPassiveTraffic(level);

        // Auto-migrate old graphs without pathBlocks
        boolean needsRecalculation = false;
        for (NetworkEdge edge : edges) {
            if (edge.getPathBlocks() == null || edge.getPathBlocks().isEmpty()) {
                needsRecalculation = true;
                break;
            }
        }
        if (needsRecalculation) {
            NetworkTracer.recalculateNetwork(level);
            return; // Skip this tick, it will resume next tick
        }

        // Reset current usage
        for (NetworkEdge edge : edges) {
            edge.setCurrentUsage(0);
        }
        
        Map<BlockPos, Integer> blockUsageDown = new HashMap<>();
        Map<BlockPos, Integer> blockUsageUp = new HashMap<>();
        
        totalBandwidthUp = 0;
        totalBandwidthDown = 0;
        
        List<TrafficSession> toRemove = new ArrayList<>();
        Map<TrafficSession, List<NetworkEdge>> sessionPaths = new HashMap<>();
        Map<TrafficSession, Integer> sessionRequested = new HashMap<>();
        
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
                    // Accumulate requested usage to compute congestion PER BLOCK
                    for (NetworkEdge edge : path) {
                        edge.setCurrentUsage(edge.getCurrentUsage() + requested);
                        if (edge.getPathBlocks() != null) {
                            for (BlockPos pos : edge.getPathBlocks()) {
                                if (session.getState() == TrafficSession.SessionState.DOWNLOAD) {
                                    blockUsageDown.put(pos, blockUsageDown.getOrDefault(pos, 0) + requested);
                                } else {
                                    blockUsageUp.put(pos, blockUsageUp.getOrDefault(pos, 0) + requested);
                                }
                                blockCapacity.put(pos, edge.getBandwidthMax());
                            }
                        }
                    }
                }
            }
            
            // Broadcast state
            if (!session.isPassive() && session.getTicksElapsed() % 2 == 0) { // Every 2 ticks to reduce spam
                com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload update = new com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload(
                    session.getClientIp(), session.getState().name(), session.getPingMs(), session.getActualBandwidth(), session.getTicksElapsed(), session.getTotalTicksPerPhase());
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
                if (edge.getPathBlocks() != null) {
                    for (BlockPos pos : edge.getPathBlocks()) {
                        int usage = session.getState() == TrafficSession.SessionState.DOWNLOAD ? blockUsageDown.getOrDefault(pos, 0) : blockUsageUp.getOrDefault(pos, 0);
                        int cap = blockCapacity.getOrDefault(pos, edge.getBandwidthMax());
                        if (usage > cap) {
                            float ratio = (float) cap / usage;
                            if (ratio < minRatio) {
                                minRatio = ratio;
                            }
                        }
                    }
                } else {
                    // Fallback to edge usage
                    if (edge.getCurrentUsage() > edge.getBandwidthMax()) {
                        float ratio = (float) edge.getBandwidthMax() / edge.getCurrentUsage();
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
        
        // Edge usage is kept for the Network Tool until the next tick
        actualBlockUsageDown.clear();
        actualBlockUsageUp.clear();
        for (Map.Entry<TrafficSession, Integer> entry : sessionRequested.entrySet()) {
            TrafficSession session = entry.getKey();
            int actual = session.getActualBandwidth();
            List<NetworkEdge> path = sessionPaths.get(session);
            for(NetworkEdge edge : path) {
                edge.setCurrentUsage(edge.getCurrentUsage() + actual);
                if (edge.getPathBlocks() != null) {
                    for (BlockPos pos : edge.getPathBlocks()) {
                        if (session.getState() == TrafficSession.SessionState.DOWNLOAD) {
                            actualBlockUsageDown.put(pos, actualBlockUsageDown.getOrDefault(pos, 0) + actual);
                        } else {
                            actualBlockUsageUp.put(pos, actualBlockUsageUp.getOrDefault(pos, 0) + actual);
                        }
                    }
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

    private List<NetworkEdge> findShortestPath(BlockPos start, BlockPos end) {
        if (start.equals(end)) return new ArrayList<>();
        
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
