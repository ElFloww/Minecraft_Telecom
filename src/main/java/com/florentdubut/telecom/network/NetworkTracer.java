package com.florentdubut.telecom.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import com.florentdubut.telecom.registry.ModBlocks;

import java.util.*;

public class NetworkTracer {

    public static void scheduleRecalculation(ServerLevel level) {
        TelecomNetworkGraph.get(level).markForRecalculation();
    }

    /**
     * Returns the EdgeType for a given cable block state, or null if it's not a cable.
     */
    public static NetworkEdge.EdgeType getCableType(BlockState state) {
        if (state.is(ModBlocks.COPPER_CABLE.get())) return NetworkEdge.EdgeType.COPPER;
        if (state.is(ModBlocks.FIBER_CABLE.get())) return NetworkEdge.EdgeType.FIBER;
        if (state.is(ModBlocks.MEDIUM_FIBER_CABLE.get())) return NetworkEdge.EdgeType.MEDIUM_FIBER;
        if (state.is(ModBlocks.BIG_FIBER_CABLE.get())) return NetworkEdge.EdgeType.BIG_FIBER;
        return null;
    }

    public static boolean doesNodeAcceptCable(NetworkNode.NodeType node, NetworkEdge.EdgeType cable) {
        return switch (node) {
            case SERVER, NRO -> cable == NetworkEdge.EdgeType.BIG_FIBER || cable == NetworkEdge.EdgeType.MEDIUM_FIBER || cable == NetworkEdge.EdgeType.FIBER;
            case NRA -> cable == NetworkEdge.EdgeType.BIG_FIBER || cable == NetworkEdge.EdgeType.MEDIUM_FIBER || cable == NetworkEdge.EdgeType.FIBER || cable == NetworkEdge.EdgeType.COPPER;
            case PM -> cable == NetworkEdge.EdgeType.MEDIUM_FIBER || cable == NetworkEdge.EdgeType.FIBER;
            case SR -> cable == NetworkEdge.EdgeType.FIBER || cable == NetworkEdge.EdgeType.COPPER;
            case ROUTER, ANTENNA -> cable == NetworkEdge.EdgeType.FIBER || cable == NetworkEdge.EdgeType.COPPER;
            case PHONE -> false;
        };
    }

    public static boolean isCableCompatibleWithNodes(NetworkEdge.EdgeType cableType, NetworkNode.NodeType a, NetworkNode.NodeType b) {
        if (!doesNodeAcceptCable(a, cableType) || !doesNodeAcceptCable(b, cableType)) return false;
        
        int tierA = getTier(a);
        int tierB = getTier(b);
        // Prevent connections between nodes of the same tier (e.g. Router to Router, PM to PM)
        // EXCEPT for core network components (Server and NROs) which can form rings/mesh.
        if (tierA == tierB && tierA > 2) return false;
        
        return true;
    }

    private static int getTier(NetworkNode.NodeType type) {
        return switch (type) {
            case SERVER -> 1;
            case NRO, NRA -> 2;
            case PM, SR -> 3;
            case ROUTER, ANTENNA -> 4;
            case PHONE -> 5;
        };
    }

    // Call this whenever a cable, server, router, or antenna is placed or broken
    public static void recalculateNetwork(ServerLevel level) {
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);

        // 1. Prepare new edges list
        java.util.List<NetworkEdge> newEdges = new java.util.ArrayList<>();

        // 2. Clear all IPs
        for (NetworkNode node : graph.getNodes()) {
            node.setIpAddress(null);
            node.setNetworkCidr(null);
        }

        // 3. BFS from each node, only crossing cables of one consistent type per segment
        Set<String> discoveredEdges = new HashSet<>();

        for (NetworkNode startNode : graph.getNodes()) {
            BlockPos startPos = startNode.getPosition();
            level.getChunk(startPos.getX() >> 4, startPos.getZ() >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);

            Queue<TraceStep> queue = new LinkedList<>();
            Set<BlockPos> visited = new HashSet<>();

            // Try each possible cable type outward from this node
            for (NetworkEdge.EdgeType startType : NetworkEdge.EdgeType.values()) {
                queue.clear();
                visited.clear();

                queue.add(new TraceStep(startPos, 0, startType, new ArrayList<>()));
                visited.add(startPos);

                while (!queue.isEmpty()) {
                    TraceStep current = queue.poll();
                    if (current.distance > 10000) continue;

                    for (Direction dir : Direction.values()) {
                        BlockPos neighbor = current.pos.relative(dir);
                        if (visited.contains(neighbor)) continue;

                        level.getChunk(neighbor.getX() >> 4, neighbor.getZ() >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
                        BlockState state = level.getBlockState(neighbor);

                        // Is it a node?
                        NetworkNode targetNode = graph.getNode(neighbor);
                        if (targetNode != null) {
                            // Check cable/node compatibility
                            if (!isCableCompatibleWithNodes(current.type, startNode.getType(), targetNode.getType())) {
                                visited.add(neighbor);
                                continue; // Invalid architectural link, skip
                            }

                            String edgeKey1 = startPos.toShortString() + "-" + neighbor.toShortString() + "-" + current.type.name();
                            String edgeKey2 = neighbor.toShortString() + "-" + startPos.toShortString() + "-" + current.type.name();

                            if (!discoveredEdges.contains(edgeKey1) && !discoveredEdges.contains(edgeKey2)) {
                                discoveredEdges.add(edgeKey1);

                                int bandwidth = switch (current.type) {
                                    case BIG_FIBER -> 1_000_000;
                                    case MEDIUM_FIBER -> 100_000;
                                    case FIBER -> 10_000;
                                    case COPPER -> 1_000;
                                };

                                List<BlockPos> finalPath = new ArrayList<>(current.pathBlocks);
                                finalPath.add(neighbor);
                                NetworkEdge edge = new NetworkEdge(startPos, neighbor, bandwidth, current.distance + 1, current.type, finalPath);
                                newEdges.add(edge);
                            }
                            visited.add(neighbor);
                            continue;
                        }

                        // Is it a cable of the same type?
                        NetworkEdge.EdgeType cableType = getCableType(state);
                        if (cableType != null && cableType == current.type) {
                            visited.add(neighbor);
                            List<BlockPos> newPath = new ArrayList<>(current.pathBlocks);
                            newPath.add(neighbor);
                            queue.add(new TraceStep(neighbor, current.distance + 1, current.type, newPath));
                        }
                        // Different cable type: stop propagation — cables of different diameters don't mix
                    }
                }
            }
        }

        // 4. Assign IPs hierarchically (CIDR tree)
        assignHierarchicalIps(graph);
    }

    private static void assignHierarchicalIps(TelecomNetworkGraph graph) {
        int nroIndex = 1;
        Map<Integer, Integer> pmIndexMap = new HashMap<>(); // X -> next Y
        Map<String, Integer> deviceIndexMap = new HashMap<>(); // parentCidr -> next Z

        Map<BlockPos, List<BlockPos>> adj = new HashMap<>();
        for (NetworkEdge edge : graph.getEdges()) {
            adj.computeIfAbsent(edge.getNodeA(), k -> new ArrayList<>()).add(edge.getNodeB());
            adj.computeIfAbsent(edge.getNodeB(), k -> new ArrayList<>()).add(edge.getNodeA());
        }

        Queue<NetworkNode> queue = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();

        // Seed with servers
        // Seed with servers
        for (NetworkNode node : graph.getNodes()) {
            if (node.getType() == NetworkNode.NodeType.SERVER) {
                node.setIpAddress("0.0.0.0");
                node.setNetworkCidr("0.0.0.0/0");
                queue.add(node);
                visited.add(node.getPosition());
            }
        }

        // Seed with NROs that aren't connected to servers
        for (NetworkNode node : graph.getNodes()) {
            if (node.getType() == NetworkNode.NodeType.NRO && !visited.contains(node.getPosition())) {
                int currentX = nroIndex++;
                node.setIpAddress("10." + currentX + ".0.1");
                node.setNetworkCidr("10." + currentX + ".0.0/16");
                queue.add(node);
                visited.add(node.getPosition());
            }
        }

        while (!queue.isEmpty()) {
            NetworkNode current = queue.poll();
            List<BlockPos> neighbors = adj.getOrDefault(current.getPosition(), Collections.emptyList());

            for (BlockPos neighborPos : neighbors) {
                if (visited.contains(neighborPos)) continue;
                NetworkNode neighbor = graph.getNode(neighborPos);
                if (neighbor == null) continue;

                visited.add(neighborPos);

                String parentCidr = current.getNetworkCidr();
                int x = 0;
                int y = 0;

                if (parentCidr != null) {
                    String[] parts = parentCidr.split("[./]");
                    if (parts.length >= 2) x = Integer.parseInt(parts[1]);
                    if (parts.length >= 3) y = Integer.parseInt(parts[2]);
                }

                switch (neighbor.getType()) {
                    case NRO -> {
                        int currentX = nroIndex++;
                        neighbor.setIpAddress("10." + currentX + ".0.1");
                        neighbor.setNetworkCidr("10." + currentX + ".0.0/16");
                    }
                    case PM -> {
                        int currentY = pmIndexMap.computeIfAbsent(x, k -> 1);
                        pmIndexMap.put(x, currentY + 1);
                        neighbor.setIpAddress("10." + x + "." + currentY + ".1");
                        neighbor.setNetworkCidr("10." + x + "." + currentY + ".0/24");
                    }
                    case ROUTER, ANTENNA, NRA, SR -> {
                        if (parentCidr != null && parentCidr.contains("/24")) {
                            int z = deviceIndexMap.computeIfAbsent(parentCidr, k -> 2);
                            deviceIndexMap.put(parentCidr, z + 1);
                            neighbor.setIpAddress("10." + x + "." + y + "." + z);
                            neighbor.setNetworkCidr(parentCidr);
                        } else if (parentCidr != null && parentCidr.contains("/16")) {
                            int z = deviceIndexMap.computeIfAbsent(parentCidr, k -> 2);
                            deviceIndexMap.put(parentCidr, z + 1);
                            neighbor.setIpAddress("10." + x + ".0." + z);
                            neighbor.setNetworkCidr(parentCidr);
                        } else {
                            neighbor.setIpAddress("10.0.0." + (100 + visited.size()));
                            neighbor.setNetworkCidr("10.0.0.0/24");
                        }
                    }
                    default -> {} 
                }

                queue.add(neighbor);
            }
        }

        graph.setDirty();
    }

    private static class TraceStep {
        final BlockPos pos;
        final int distance;
        final NetworkEdge.EdgeType type;
        final List<BlockPos> pathBlocks;

        TraceStep(BlockPos pos, int distance, NetworkEdge.EdgeType type, List<BlockPos> pathBlocks) {
            this.pos = pos;
            this.distance = distance;
            this.type = type;
            this.pathBlocks = pathBlocks;
        }
    }
}
