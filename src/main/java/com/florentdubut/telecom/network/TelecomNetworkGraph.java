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
            NetworkEdge edge = new NetworkEdge(nodeA, nodeB, bandwidthMax, length, type);
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
