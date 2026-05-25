package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import com.florentdubut.telecom.registry.ModBlocks;

import java.util.*;

public class NetworkTracer {

    // Call this whenever a cable, server, router, or antenna is placed or broken
    public static void recalculateNetwork(ServerLevel level) {
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
        
        // 1. Clear all current edges
        graph.clearEdges();
        
        // 2. Clear all IPs except Servers
        for (NetworkNode node : graph.getNodes()) {
            if (node.getType() != NetworkNode.NodeType.SERVER) {
                node.setIpAddress(null);
            }
        }
        
        // 3. For each node, trace outward to find connected nodes
        Set<String> discoveredEdges = new HashSet<>(); // e.g. "x1,y1,z1-x2,y2,z2"

        for (NetworkNode startNode : graph.getNodes()) {
            BlockPos startPos = startNode.getPosition();
            if (!level.isLoaded(startPos)) continue;

            Queue<TraceStep> queue = new LinkedList<>();
            Set<BlockPos> visited = new HashSet<>();
            
            queue.add(new TraceStep(startPos, 0, NetworkEdge.EdgeType.FIBER)); // Start with fiber, downgrade to copper if needed
            visited.add(startPos);
            
            while (!queue.isEmpty()) {
                TraceStep current = queue.poll();
                
                // Max length 10000 blocks to prevent infinite lag loops
                if (current.distance > 10000) continue;
                
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.pos.relative(dir);
                    if (visited.contains(neighbor)) continue;
                    if (!level.isLoaded(neighbor)) continue;
                    
                    BlockState state = level.getBlockState(neighbor);
                    
                    // Is it a node?
                    NetworkNode targetNode = graph.getNode(neighbor);
                    if (targetNode != null) {
                        // Found a connection
                        String edgeKey1 = startPos.toShortString() + "-" + neighbor.toShortString();
                        String edgeKey2 = neighbor.toShortString() + "-" + startPos.toShortString();
                        
                        if (!discoveredEdges.contains(edgeKey1) && !discoveredEdges.contains(edgeKey2)) {
                            discoveredEdges.add(edgeKey1);
                            
                            int bandwidth = current.type == NetworkEdge.EdgeType.COPPER ? 1000 : 10000;
                            NetworkEdge edge = new NetworkEdge(startPos, neighbor, bandwidth, current.distance + 1, current.type);
                            graph.addEdge(edge);
                        }
                        visited.add(neighbor);
                        continue;
                    }
                    
                    // Is it a cable?
                    boolean isCopper = state.is(ModBlocks.COPPER_CABLE.get());
                    boolean isFiber = state.is(ModBlocks.FIBER_CABLE.get());
                    
                    if (isCopper || isFiber) {
                        visited.add(neighbor);
                        NetworkEdge.EdgeType nextType = isCopper ? NetworkEdge.EdgeType.COPPER : current.type;
                        queue.add(new TraceStep(neighbor, current.distance + 1, nextType));
                    }
                }
            }
        }
        
        // 4. Assign IPs via DHCP from Servers outward (BFS on Graph)
        assignIps(graph);
    }
    
    private static void assignIps(TelecomNetworkGraph graph) {
        int ipCounter = 2; // Start from 192.168.0.2
        Queue<NetworkNode> queue = new LinkedList<>();
        Set<BlockPos> assigned = new HashSet<>();
        
        for (NetworkNode node : graph.getNodes()) {
            if (node.getType() == NetworkNode.NodeType.SERVER) {
                node.setIpAddress("192.168.0.1"); // Simple fallback, if multiple servers they might conflict, but fine for now
                queue.add(node);
                assigned.add(node.getPosition());
            }
        }
        
        while (!queue.isEmpty()) {
            NetworkNode current = queue.poll();
            
            for (NetworkEdge edge : graph.getEdges()) {
                NetworkNode neighbor = null;
                if (edge.getNodeA().equals(current.getPosition())) {
                    neighbor = graph.getNode(edge.getNodeB());
                } else if (edge.getNodeB().equals(current.getPosition())) {
                    neighbor = graph.getNode(edge.getNodeA());
                }
                
                if (neighbor != null && !assigned.contains(neighbor.getPosition())) {
                    neighbor.setIpAddress("192.168.0." + ipCounter++);
                    assigned.add(neighbor.getPosition());
                    queue.add(neighbor);
                }
            }
        }
        
        graph.setDirty();
    }

    private static class TraceStep {
        final BlockPos pos;
        final int distance;
        final NetworkEdge.EdgeType type;

        TraceStep(BlockPos pos, int distance, NetworkEdge.EdgeType type) {
            this.pos = pos;
            this.distance = distance;
            this.type = type;
        }
    }
}
