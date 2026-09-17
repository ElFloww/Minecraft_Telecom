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

        // Trace cables without discarding the persisted addresses.
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

        graph.setEdges(newEdges);

        graph.ensureFixedAddresses();
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
