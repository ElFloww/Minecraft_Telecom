package com.florentdubut.telecom.item;

import com.florentdubut.telecom.network.NetworkEdge;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

public class NetworkToolItem extends Item {

    public NetworkToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockPos clickedPos = context.getClickedPos();
        BlockState state = level.getBlockState(clickedPos);
        
        if (state.is(ModBlocks.COPPER_CABLE.get()) || state.is(ModBlocks.FIBER_CABLE.get())) {
            ServerLevel serverLevel = (ServerLevel) level;
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            
            // Perform BFS from clicked cable to find the two end nodes
            Set<BlockPos> visited = new HashSet<>();
            Queue<BlockPos> queue = new LinkedList<>();
            List<BlockPos> foundNodes = new ArrayList<>();
            
            queue.add(clickedPos);
            visited.add(clickedPos);
            
            while (!queue.isEmpty() && foundNodes.size() < 2) {
                BlockPos current = queue.poll();
                
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.relative(dir);
                    if (visited.contains(neighbor)) continue;
                    
                    BlockState neighborState = level.getBlockState(neighbor);
                    if (neighborState.is(ModBlocks.COPPER_CABLE.get()) || neighborState.is(ModBlocks.FIBER_CABLE.get())) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    } else if (graph.getNode(neighbor) != null) {
                        if (!foundNodes.contains(neighbor)) {
                            foundNodes.add(neighbor);
                        }
                    }
                }
            }
            
            if (foundNodes.size() == 2) {
                // Find the edge in the graph
                for (NetworkEdge edge : graph.getEdges()) {
                    if ((edge.getNodeA().equals(foundNodes.get(0)) && edge.getNodeB().equals(foundNodes.get(1))) ||
                        (edge.getNodeA().equals(foundNodes.get(1)) && edge.getNodeB().equals(foundNodes.get(0)))) {
                        
                        // We found the edge! Send Payload
                        String typeStr = edge.getType() == NetworkEdge.EdgeType.FIBER ? "Fiber Optic" : "Copper ADSL";
                        // Real usage from physical block
                        int usageDown = graph.getActualBlockUsageDown(clickedPos);
                        int usageUp = graph.getActualBlockUsageUp(clickedPos);
                        
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                            (net.minecraft.server.level.ServerPlayer) context.getPlayer(),
                            new com.florentdubut.telecom.network.packet.NetworkToolSyncPayload(clickedPos, typeStr, edge.getLength(), edge.getBandwidthMax(), usageDown, usageUp)
                        );
                        return InteractionResult.SUCCESS;
                    }
                }
            } else if (foundNodes.size() == 1) {
                context.getPlayer().sendSystemMessage(net.minecraft.network.chat.Component.literal("Cable is only connected to one end. Incomplete path."));
            } else {
                context.getPlayer().sendSystemMessage(net.minecraft.network.chat.Component.literal("Cable is not connected to any network nodes."));
            }
        }
        
        return InteractionResult.PASS;
    }
}
