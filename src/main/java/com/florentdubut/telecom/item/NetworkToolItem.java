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
        
        if (state.is(ModBlocks.COPPER_CABLE.get()) || state.is(ModBlocks.FIBER_CABLE.get()) ||
            state.is(ModBlocks.MEDIUM_FIBER_CABLE.get()) || state.is(ModBlocks.BIG_FIBER_CABLE.get()) ||
            state.getBlock() instanceof com.florentdubut.telecom.block.TelecomHubBlock ||
            state.is(ModBlocks.ROUTER.get()) || state.is(ModBlocks.SERVER.get()) || state.is(ModBlocks.ANTENNA.get())) {
            
            ServerLevel serverLevel = (ServerLevel) level;
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            
            // If they clicked on a node directly, we can just show the max node capacity or sum of edges
            // But to reuse the UI, we can just pass the node's usage.
            com.florentdubut.telecom.network.NetworkNode clickedNode = graph.getNode(clickedPos);
            if (clickedNode != null) {
                int usageDown = clickedNode.getCurrentUsageDown();
                int usageUp = clickedNode.getCurrentUsageUp();
                int maxBandwidth = 0;
                // Sum usage of all edges connected to this node
                for (NetworkEdge edge : graph.getEdges()) {
                    if (edge.getNodeA().equals(clickedPos) || edge.getNodeB().equals(clickedPos)) {
                        maxBandwidth = Math.max(maxBandwidth, edge.getBandwidthMax());
                    }
                }
                String typeStr = "Network Node (" + clickedNode.getType().name() + ")";
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    (net.minecraft.server.level.ServerPlayer) context.getPlayer(),
                    new com.florentdubut.telecom.network.packet.NetworkToolSyncPayload(clickedPos, typeStr, 0, maxBandwidth == 0 ? 1000 : maxBandwidth, usageDown, usageUp)
                );
                return InteractionResult.SUCCESS;
            }
            
            // Find the edge containing this cable block
            NetworkEdge clickedEdge = null;
            for (NetworkEdge edge : graph.getEdges()) {
                if (edge.getPathBlocks() != null && edge.getPathBlocks().contains(clickedPos)) {
                    clickedEdge = edge;
                    break;
                }
            }
            
            if (clickedEdge != null) {
                // We found the edge! Send Payload
                String typeStr = "Unknown";
                if (clickedEdge.getType() == NetworkEdge.EdgeType.BIG_FIBER) typeStr = "Big Fiber Optic";
                else if (clickedEdge.getType() == NetworkEdge.EdgeType.MEDIUM_FIBER) typeStr = "Medium Fiber Optic";
                else if (clickedEdge.getType() == NetworkEdge.EdgeType.FIBER) typeStr = "Fiber Optic";
                else if (clickedEdge.getType() == NetworkEdge.EdgeType.COPPER) typeStr = "Copper ADSL";
                // Real usage from physical block
                int usageDown = graph.getActualBlockUsageDown(clickedPos);
                int usageUp = graph.getActualBlockUsageUp(clickedPos);
                
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    (net.minecraft.server.level.ServerPlayer) context.getPlayer(),
                    new com.florentdubut.telecom.network.packet.NetworkToolSyncPayload(clickedPos, typeStr, clickedEdge.getLength(), clickedEdge.getBandwidthMax(), usageDown, usageUp)
                );
                return InteractionResult.SUCCESS;
            } else {
                context.getPlayer().sendSystemMessage(net.minecraft.network.chat.Component.literal("Cable is incomplete or not connected to any network nodes."));
            }
        }
        
        return InteractionResult.PASS;
    }
}
