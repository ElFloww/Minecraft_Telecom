package com.florentdubut.telecom.event;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.network.NetworkTracer;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = TelecomMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class CommandEvents {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("telecom")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("recalculate")
                    .executes(context -> {
                        CommandSourceStack source = context.getSource();
                        ServerLevel level = source.getLevel();
                        
                        NetworkTracer.recalculateNetwork(level);
                        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
                        
                        source.sendSuccess(() -> Component.literal("Telecom network recalculated! Nodes: " + graph.getNodes().size() + ", Edges: " + graph.getEdges().size()), true);
                        return 1;
                    })
                )
        );
    }
}
