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

    /**
     * Returns whether a cable type is allowed to connect to a node of a given type.
     * Only edges that are architecturally valid are created:
     *   BIG_FIBER   → SERVER ↔ NRO or NRO ↔ NRO
     *   MEDIUM_FIBER → NRO ↔ PM
     *   FIBER/COPPER → PM ↔ ROUTER or PM ↔ ANTENNA
     */
    public static boolean isCableCompatibleWithNodes(NetworkEdge.EdgeType cableType, NetworkNode.NodeType a, NetworkNode.NodeType b) {
        // Normalize: sort so we don't need to check both directions
        Set<NetworkNode.NodeType> pair = new HashSet<>(Arrays.asList(a, b));
        return switch (cableType) {
            case BIG_FIBER -> (pair.contains(NetworkNode.NodeType.SERVER) || pair.contains(NetworkNode.NodeType.NRO))
                              && !pair.contains(NetworkNode.NodeType.PM)
                              && !pair.contains(NetworkNode.NodeType.ROUTER)
                              && !pair.contains(NetworkNode.NodeType.ANTENNA);
            case MEDIUM_FIBER -> pair.contains(NetworkNode.NodeType.NRO) && pair.contains(NetworkNode.NodeType.PM);
            case FIBER -> (pair.contains(NetworkNode.NodeType.PM) || pair.contains(NetworkNode.NodeType.NRA) || pair.contains(NetworkNode.NodeType.SR))
                         && (pair.contains(NetworkNode.NodeType.ROUTER) || pair.contains(NetworkNode.NodeType.ANTENNA)
                             || pair.contains(NetworkNode.NodeType.NRA) || pair.contains(NetworkNode.NodeType.SR)
                             || pair.contains(NetworkNode.NodeType.PM));
            case COPPER -> pair.contains(NetworkNode.NodeType.PM) 
                           && (pair.contains(NetworkNode.NodeType.ROUTER) || pair.contains(NetworkNode.NodeType.ANTENNA));
        };
    }

    // Call this whenever a cable, server, router, or antenna is placed or broken
    public static void recalculateNetwork(ServerLevel level) {
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);

        // 1. Clear all current edges
        graph.clearEdges();

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
                                graph.addEdge(edge);
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

    /**
     * Assigns IPs hierarchically:
     *   Server: 0.0.0.0/0 (internet gateway)
     *   NRO:    10.X.0.1  (gateway of /16: 10.X.0.0/16), X = 1..254
     *   PM:     10.X.Y.1  (gateway of /24: 10.X.Y.0/24), Y = 1..254
     *   Router/Antenna: 10.X.Y.Z, Z = 2..254
     */
    private static void assignHierarchicalIps(TelecomNetworkGraph graph) {
        // Start from Servers
        int nroIndex = 1;

        // Build adjacency for BFS
        Map<BlockPos, List<BlockPos>> adj = new HashMap<>();
        for (NetworkEdge edge : graph.getEdges()) {
            adj.computeIfAbsent(edge.getNodeA(), k -> new ArrayList<>()).add(edge.getNodeB());
            adj.computeIfAbsent(edge.getNodeB(), k -> new ArrayList<>()).add(edge.getNodeA());
        }

        Queue<NetworkNode> queue = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();

        // Seed with servers
        for (NetworkNode node : graph.getNodes()) {
            if (node.getType() == NetworkNode.NodeType.SERVER) {
                node.setIpAddress("0.0.0.0");
                node.setNetworkCidr("0.0.0.0/0");
                queue.add(node);
                visited.add(node.getPosition());
            }
        }

        // BFS outward, assigning IPs by layer
        while (!queue.isEmpty()) {
            NetworkNode current = queue.poll();
            List<BlockPos> neighbors = adj.getOrDefault(current.getPosition(), Collections.emptyList());

            for (BlockPos neighborPos : neighbors) {
                if (visited.contains(neighborPos)) continue;
                NetworkNode neighbor = graph.getNode(neighborPos);
                if (neighbor == null) continue;

                visited.add(neighborPos);

                // Determine IP based on parent context
                switch (neighbor.getType()) {
                    case NRO -> {
                        int x = nroIndex++;
                        neighbor.setIpAddress("10." + x + ".0.1");
                        neighbor.setNetworkCidr("10." + x + ".0.0/16");
                    }
                    case PM -> {
                        // Determine which NRO is the parent (the current node or its parent)
                        String parentCidr = current.getNetworkCidr();
                        if (parentCidr != null && parentCidr.contains("/16")) {
                            // parent is NRO: extract X
                            String[] parts = parentCidr.split("\\.");
                            int x = Integer.parseInt(parts[1]);
                            int y = countChildrenOfType(graph, adj, current, NetworkNode.NodeType.PM, visited) + 1;
                            neighbor.setIpAddress("10." + x + "." + y + ".1");
                            neighbor.setNetworkCidr("10." + x + "." + y + ".0/24");
                        } else {
                            neighbor.setIpAddress("10.0.1.1");
                            neighbor.setNetworkCidr("10.0.1.0/24");
                        }
                    }
                    case ROUTER, ANTENNA, NRA, SR -> {
                        String parentCidr = current.getNetworkCidr();
                        if (parentCidr != null && parentCidr.contains("/24")) {
                            String[] parts = parentCidr.split("[./]");
                            int x = Integer.parseInt(parts[1]);
                            int y = Integer.parseInt(parts[2]);
                            int z = countChildrenOfType(graph, adj, current, neighbor.getType(), visited) + 2;
                            neighbor.setIpAddress("10." + x + "." + y + "." + z);
                            neighbor.setNetworkCidr(parentCidr);
                        } else if (parentCidr != null && parentCidr.contains("/16")) {
                            // Directly under NRO (e.g. antenna or router without PM) — legacy fallback
                            String[] parts = parentCidr.split("[./]");
                            int x = Integer.parseInt(parts[1]);
                            int z = countChildrenOfType(graph, adj, current, neighbor.getType(), visited) + 2;
                            neighbor.setIpAddress("10." + x + ".0." + z);
                            neighbor.setNetworkCidr(parentCidr);
                        } else {
                            neighbor.setIpAddress("10.0.0." + (100 + visited.size()));
                            neighbor.setNetworkCidr("10.0.0.0/24");
                        }
                    }
                    default -> {} // SERVER handled above, PHONE not in graph
                }

                queue.add(neighbor);
            }
        }

        graph.setDirty();
    }

    /** Counts already-assigned children of a given type from a parent node, to generate unique Z/Y indices */
    private static int countChildrenOfType(TelecomNetworkGraph graph, Map<BlockPos, List<BlockPos>> adj,
                                           NetworkNode parent, NetworkNode.NodeType childType,
                                           Set<BlockPos> alreadyVisited) {
        int count = 0;
        List<BlockPos> neighbors = adj.getOrDefault(parent.getPosition(), Collections.emptyList());
        for (BlockPos nPos : neighbors) {
            NetworkNode n = graph.getNode(nPos);
            if (n != null && n.getType() == childType && alreadyVisited.contains(nPos) && n.getIpAddress() != null) {
                count++;
            }
        }
        return count;
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
