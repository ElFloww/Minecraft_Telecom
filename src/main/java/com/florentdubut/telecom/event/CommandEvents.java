package com.florentdubut.telecom.event;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.network.NetworkTracer;
import com.florentdubut.telecom.network.NetworkDiagnostics;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = TelecomMod.MODID)
public class CommandEvents {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("telecom")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("diagnostics")
                    .executes(context -> diagnostics(context.getSource(), "status"))
                    .then(Commands.literal("start").executes(context -> diagnostics(context.getSource(), "start")))
                    .then(Commands.literal("stop").executes(context -> diagnostics(context.getSource(), "stop")))
                    .then(Commands.literal("status").executes(context -> diagnostics(context.getSource(), "status")))
                )
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

    private static int diagnostics(CommandSourceStack source, String action) {
        NetworkDiagnostics diagnostics = TelecomNetworkGraph.get(source.getLevel()).getDiagnostics();
        if (action.equals("start")) diagnostics.start();
        if (action.equals("stop")) diagnostics.stop();
        String dimension = source.getLevel().dimension().identifier().toString();
        for (String line : diagnostics.report()) {
            source.sendSuccess(() -> Component.literal("[" + dimension + "] " + line), false);
            org.slf4j.LoggerFactory.getLogger(NetworkDiagnostics.class).info("[{}] {}", dimension, line);
        }
        return 1;
    }
}
