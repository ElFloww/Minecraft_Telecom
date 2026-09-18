package com.florentdubut.telecom.gametest;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.network.NetworkDiagnostics;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.NetworkTracer;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/** Real-world baseline, deliberately opt-in and separate from correctness test batches. */
@EventBusSubscriber(modid = TelecomMod.MODID)
public final class NetworkBaselineGameTests {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(TelecomMod.MODID, "network_baseline");
    private static final int ROWS = 20;
    private static final int MUTATIONS = 40;

    @SubscribeEvent
    public static void registerFunction(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry -> registry.register(ID, NetworkBaselineGameTests::baseline));
    }

    @SubscribeEvent
    public static void registerTest(RegisterGameTestsEvent event) {
        if (!Boolean.getBoolean("telecom.networkBaseline")) return;
        var environment = event.registerEnvironment(ID);
        event.registerTest(ID, data -> new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, ID), data),
                new TestData<>(environment, ID, 600, 0, true));
    }

    @SubscribeEvent
    public static void installStructure(ServerAboutToStartEvent event) {
        if (!Boolean.getBoolean("telecom.networkBaseline") || !(event.getServer() instanceof GameTestServer)) return;
        CompoundTag structure = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(48));
        size.add(IntTag.valueOf(4));
        size.add(IntTag.valueOf(84));
        structure.put("size", size);
        structure.put("blocks", new ListTag());
        structure.put("entities", new ListTag());
        ListTag palette = new ListTag();
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        structure.put("palette", palette);
        event.getServer().getStructureManager().getOrCreate(ID)
                .load(event.getServer().registryAccess().lookupOrThrow(Registries.BLOCK), structure);
    }

    private static void baseline(GameTestHelper helper) {
        helper.assertTrue(helper.getLevel().getServer() instanceof GameTestServer, "Only run in an isolated GameTest world");
        var graph = TelecomNetworkGraph.get(helper.getLevel());
        var diagnostics = graph.getDiagnostics();
        Map<BlockPos, String> addresses = new HashMap<>();
        for (int row = 0; row < ROWS; row++) {
            int z = 2 + row * 4;
            helper.setBlock(new BlockPos(1, 1, z), ModBlocks.SERVER.get());
            for (int x = 2; x <= 44; x++) helper.setBlock(new BlockPos(x, 1, z), ModBlocks.FIBER_CABLE.get());
            for (int router = 0; router < 10; router++) {
                helper.setBlock(new BlockPos(4 + router * 4, 1, z + 1), ModBlocks.ROUTER_LITE.get());
            }
            helper.setBlock(new BlockPos(44, 1, z + 1), ModBlocks.ANTENNA.get());
        }
        var sequence = helper.startSequence().thenIdle(10).thenExecute(() -> {
            helper.assertValueEqual(graph.getNodes().stream().filter(n -> n.getType() == NetworkNode.NodeType.ROUTER).count(),
                    200L, "Baseline routers");
            helper.assertValueEqual(graph.getNodes().stream().filter(n -> n.getType() == NetworkNode.NodeType.ANTENNA).count(),
                    20L, "Baseline antennas");
            helper.assertValueEqual(graph.getEdges().size(), 220, "Twenty independent branches with eleven terminals each");
            graph.getNodes().forEach(node -> addresses.put(node.getPosition(), node.getIpAddress()));
        });
        for (int warmup = 0; warmup < 8; warmup++) {
            sequence.thenExecute(() -> NetworkTracer.recalculateNetwork(helper.getLevel())).thenIdle(1);
        }
        sequence.thenExecute(() -> {
            var server = helper.getLevel().getServer();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(helper.getLevel()),
                    "telecom diagnostics start");
            helper.assertTrue(diagnostics.isEnabled(), "Admin command enables dimension diagnostics");
        }).thenIdle(40);
        BlockPos cut = new BlockPos(2, 1, 2);
        for (int mutation = 0; mutation < MUTATIONS; mutation++) {
            boolean connected = mutation % 2 != 0;
            sequence.thenExecute(() -> helper.setBlock(cut, connected ? ModBlocks.FIBER_CABLE.get() : Blocks.AIR))
                    .thenIdle(2)
                    .thenExecute(() -> helper.assertValueEqual(graph.getEdges().size(), connected ? 220 : 209,
                            "Only one branch changes connectivity"));
        }
        sequence.thenExecute(() -> graph.scheduleDelayedRecalculation(100, NetworkDiagnostics.Cause.PLAYER_LOGIN))
                .thenIdle(103)
                .thenExecute(() -> {
                    helper.assertValueEqual(diagnostics.recalculations().size(), MUTATIONS + 1,
                            "One trace per mutation and one login-triggered trace");
                    var login = diagnostics.recalculations().getLast();
                    helper.assertTrue(login.causes().containsKey(NetworkDiagnostics.Cause.PLAYER_LOGIN), "Login cause retained");
                    helper.assertValueEqual(login.waitTicks(), 101L, "Existing login timer delay");
                    for (var node : graph.getNodes()) {
                        helper.assertValueEqual(node.getIpAddress(), addresses.get(node.getPosition()), "Stable addresses");
                    }
                    helper.assertTrue(diagnostics.serverTicks().samples() >= 200, "Real server ticks are sampled");
                    var server = helper.getLevel().getServer();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(helper.getLevel()),
                            "telecom diagnostics stop");
                    helper.assertTrue(!diagnostics.isEnabled(), "Admin command stops recording");
                    var logger = LoggerFactory.getLogger(NetworkBaselineGameTests.class);
                    logger.info("NETWORK_BASELINE: routers=200 antennas=20 servers=20 cables=860 warmup=8 mutations=40; Java={} OS={} arch={}",
                            System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"));
                    diagnostics.report().forEach(line -> logger.info("NETWORK_BASELINE: {}", line));
                    diagnostics.recalculations().forEach(sample -> logger.info("NETWORK_BASELINE_SAMPLE: {}", sample));
                }).thenSucceed();
    }
}
