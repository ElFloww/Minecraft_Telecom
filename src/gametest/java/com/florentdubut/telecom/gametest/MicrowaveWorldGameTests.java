package com.florentdubut.telecom.gametest;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.block.entity.MicrowaveDishBlockEntity;
import com.florentdubut.telecom.network.MicrowaveConfig;
import com.florentdubut.telecom.network.MicrowaveLinkService;
import com.florentdubut.telecom.network.NetworkEdge;
import com.florentdubut.telecom.network.NetworkTracer;
import com.florentdubut.telecom.network.SignalPropagator;
import com.florentdubut.telecom.network.TelecomFrequency;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.network.TrafficSession;
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
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@EventBusSubscriber(modid = TelecomMod.MODID)
public final class MicrowaveWorldGameTests {
    private static final Identifier STRUCTURE = id("microwave_test_space");
    private static final Identifier TRANSPORT = id("microwave_transport_and_wall");
    private static final Identifier RELAY = id("microwave_two_hop_relay");

    @SubscribeEvent
    public static void functions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry -> {
            registry.register(TRANSPORT, MicrowaveWorldGameTests::transportAndWall);
            registry.register(RELAY, MicrowaveWorldGameTests::twoHopRelay);
        });
    }

    @SubscribeEvent
    public static void tests(RegisterGameTestsEvent event) {
        for (Identifier test : List.of(TRANSPORT, RELAY)) {
            var environment = event.registerEnvironment(id(test.getPath() + "_environment"));
            event.registerTest(test, data -> new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, test), data),
                    new TestData<>(environment, STRUCTURE, 500, 0, true));
        }
    }

    @SubscribeEvent
    public static void structure(ServerAboutToStartEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) return;
        CompoundTag structure = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(32));
        size.add(IntTag.valueOf(12));
        size.add(IntTag.valueOf(32));
        structure.put("size", size);
        structure.put("blocks", new ListTag());
        structure.put("entities", new ListTag());
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        ListTag palette = new ListTag();
        palette.add(air);
        structure.put("palette", palette);
        event.getServer().getStructureManager().getOrCreate(STRUCTURE)
                .load(event.getServer().registryAccess().lookupOrThrow(Registries.BLOCK), structure);
    }

    private static void transportAndWall(GameTestHelper helper) {
        var level = helper.getLevel();
        var graph = TelecomNetworkGraph.get(level);
        BlockPos a = new BlockPos(3, 5, 8), b = new BlockPos(19, 5, 8);
        BlockPos server = a.west(2), router = b.east(2), mobile = b.south(2), wall = a.east(8);
        BlockPos serverPos = helper.absolutePos(server), routerPos = helper.absolutePos(router);
        helper.setBlock(a.below(), net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(b.below(), net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(a, ModBlocks.MICROWAVE_DISH.get());
        helper.setBlock(b, ModBlocks.MICROWAVE_DISH.get());
        helper.setBlock(server, ModBlocks.SERVER.get());
        helper.setBlock(a.west(), ModBlocks.FIBER_CABLE.get());
        helper.setBlock(router, ModBlocks.ROUTER_LITE.get());
        helper.setBlock(b.east(), ModBlocks.FIBER_CABLE.get());
        helper.setBlock(mobile, ModBlocks.ANTENNA.get());
        helper.setBlock(b.south(), ModBlocks.FIBER_CABLE.get());
        AtomicReference<TrafficSession> session = new AtomicReference<>();
        AtomicReference<String> ip = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(graph.getNode(helper.absolutePos(a)) != null
                        && graph.getNode(helper.absolutePos(b)) != null, "Dish placement must register both graph nodes"))
                .thenExecute(() -> {
                    pair(helper, a, b, 270, 90);
                    helper.getBlockEntity(mobile, AntennaBlockEntity.class).setEnabledFrequenciesMask(1 << TelecomFrequency.G4_700.ordinal());
                })
                .thenWaitUntil(() -> {
                    var stats = graph.calculatePathStats(routerPos, serverPos);
                    helper.assertTrue(stats != null && stats.bandwidthMbps() > 0, "A remote router must obtain service over FH without a cable between sites");
                    helper.assertTrue(stats.bandwidthMbps() <= 600, "The 11 GHz FH profile must limit the end-to-end path");
                    helper.assertTrue(wireless(graph, helper.absolutePos(a), helper.absolutePos(b)) != null,
                            "The actual routing path must consume the FH edge");
                })
                .thenExecute(() -> {
                    ip.set(graph.getNode(routerPos).getIpAddress());
                    var started = graph.startSpeedtest(routerPos, ip.get(), 1000, 700, 0, 0, 300, false, null,
                            Long.toString(serverPos.asLong()));
                    helper.assertTrue(started.session() != null && started.error().isEmpty(), "FH-backed speedtest must start");
                    session.set(started.session());
                })
                .thenWaitUntil(() -> helper.assertTrue(session.get().getActualBandwidth() > 0,
                        "Traffic must actually be allocated through the wireless backhaul"))
                .thenExecute(() -> {
                    var fh = wireless(graph, helper.absolutePos(a), helper.absolutePos(b));
                    helper.assertTrue(fh.getPathBlocks().isEmpty(), "Air positions must not become shared cable resources");
                    helper.assertTrue(fh.getCurrentUsage() > 0 && fh.getCurrentUsage() <= fh.getEffectiveBandwidthMbps(), "FH telemetry must reflect actual bounded allocation");
                    NetworkTracer.recalculateNetwork(level);
                    helper.assertTrue(graph.calculatePathStats(routerPos, serverPos) != null, "A cable retrace must preserve an operational FH link");
                    var saved = TelecomNetworkGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph).getOrThrow();
                    var restored = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
                    helper.assertValueEqual(restored.getNode(helper.absolutePos(a)).getMicrowaveConfig(),
                            graph.getNode(helper.absolutePos(a)).getMicrowaveConfig(), "Pairing and radio configuration must survive graph persistence");
                    helper.assertTrue(restored.getEdges().stream().noneMatch(edge -> edge.getType() == NetworkEdge.EdgeType.MICROWAVE),
                            "Restart must revalidate derived wireless edges, not trust old clear terrain");
                    strict(helper, wall, "minecraft:stone");
                    helper.assertTrue(graph.calculatePathStats(routerPos, serverPos) == null, "Strict block writes must immediately withdraw affected FH routes");
                })
                .thenWaitUntil(() -> helper.assertTrue("blocked".equals(MicrowaveLinkService.status(level, helper.absolutePos(a)).state()),
                        "The link must expose its blocking obstacle"))
                .thenExecute(() -> strict(helper, wall, "minecraft:air"))
                .thenWaitUntil(() -> helper.assertTrue(graph.calculatePathStats(routerPos, serverPos) != null, "Removing the obstacle must restore the actual FH path"))
                .thenExecute(() -> helper.destroyBlock(a.west()))
                .thenWaitUntil(() -> {
                    helper.assertTrue(graph.calculatePathStats(routerPos, serverPos) == null, "The FH must not bypass the upstream cable dependency");
                    var signal = SignalPropagator.calculateSignal(level, helper.absolutePos(mobile), helper.absolutePos(mobile.south()), TelecomFrequency.G4_700);
                    helper.assertTrue(signal.known && signal.powerDbm > SignalPropagator.MIN_SIGNAL, "Mobile radio may remain present without upstream service");
                    helper.assertValueEqual(graph.getNode(routerPos).getIpAddress(), ip.get(), "FH outages must not renumber devices");
                })
                .thenExecute(() -> {
                    for (BlockPos pos : List.of(a, b, server, router, mobile, b.east(), b.south(), a.below(), b.below())) helper.destroyBlock(pos);
                })
                .thenSucceed();
    }

    private static void twoHopRelay(GameTestHelper helper) {
        var graph = TelecomNetworkGraph.get(helper.getLevel());
        BlockPos a = new BlockPos(3, 5, 3), west = new BlockPos(19, 5, 3);
        BlockPos south = new BlockPos(19, 5, 7), c = new BlockPos(19, 5, 23);
        BlockPos server = a.west(2), router = c.east(2);
        for (BlockPos pos : List.of(a, west, south, c)) {
            helper.setBlock(pos.below(), net.minecraft.world.level.block.Blocks.STONE);
            helper.setBlock(pos, ModBlocks.MICROWAVE_DISH.get());
        }
        helper.setBlock(server, ModBlocks.SERVER.get());
        helper.setBlock(router, ModBlocks.ROUTER_LITE.get());
        for (BlockPos pos : List.of(a.west(), c.east(), west.south(), west.south(2), west.south(3))) helper.setBlock(pos, ModBlocks.FIBER_CABLE.get());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(graph.getNode(helper.absolutePos(c)) != null, "Relay dishes must enter the graph"))
                .thenExecute(() -> {
                    pair(helper, a, west, 270, 90);
                    pair(helper, south, c, 0, 180);
                })
                .thenWaitUntil(() -> {
                    var stats = graph.calculatePathStats(helper.absolutePos(router), helper.absolutePos(server));
                    helper.assertTrue(stats != null && wireless(graph, helper.absolutePos(a), helper.absolutePos(west)) != null
                                    && wireless(graph, helper.absolutePos(south), helper.absolutePos(c)) != null,
                            "A relay must route through both FH hops and its local interconnect");
                    helper.assertTrue(stats.bandwidthMbps() <= 600 && stats.pingMs() >= 4, "Every hop must contribute its capacity and latency");
                })
                .thenExecute(() -> helper.destroyBlock(west.south(2)))
                .thenWaitUntil(() -> helper.assertTrue(graph.calculatePathStats(helper.absolutePos(router), helper.absolutePos(server)) == null,
                        "Breaking the relay's local interconnect must interrupt the end-to-end path"))
                .thenExecute(() -> {
                    for (BlockPos pos : List.of(a, west, south, c, server, router, a.west(), c.east(), west.south(), west.south(3),
                            a.below(), west.below(), south.below(), c.below())) helper.destroyBlock(pos);
                })
                .thenSucceed();
    }

    private static void pair(GameTestHelper helper, BlockPos a, BlockPos b, int azimuthA, int azimuthB) {
        helper.getBlockEntity(a, MicrowaveDishBlockEntity.class).setConfig(new MicrowaveConfig(helper.absolutePos(b), 1, 11, azimuthA, 0, true));
        helper.getBlockEntity(b, MicrowaveDishBlockEntity.class).setConfig(new MicrowaveConfig(helper.absolutePos(a), 1, 11, azimuthB, 0, true));
    }

    private static NetworkEdge wireless(TelecomNetworkGraph graph, BlockPos a, BlockPos b) {
        return graph.getEdges().stream().filter(edge -> edge.getType() == NetworkEdge.EdgeType.MICROWAVE
                && (edge.getNodeA().equals(a) && edge.getNodeB().equals(b)
                || edge.getNodeA().equals(b) && edge.getNodeB().equals(a))).findFirst().orElse(null);
    }

    private static void strict(GameTestHelper helper, BlockPos relative, String block) {
        BlockPos pos = helper.absolutePos(relative);
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block + " strict");
    }

    private static Identifier id(String name) { return Identifier.fromNamespaceAndPath(TelecomMod.MODID, name); }
}
