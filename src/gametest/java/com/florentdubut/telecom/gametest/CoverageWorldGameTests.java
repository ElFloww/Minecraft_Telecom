package com.florentdubut.telecom.gametest;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.network.CoverageService;
import com.florentdubut.telecom.network.AntennaRadioConfig;
import com.florentdubut.telecom.network.SignalPropagator;
import com.florentdubut.telecom.network.TelecomFrequency;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.registry.ModBlocks;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

import java.util.concurrent.atomic.AtomicReference;

@EventBusSubscriber(modid = TelecomMod.MODID)
public final class CoverageWorldGameTests {
    private static final Identifier TEST = id("coverage_wall_removal");
    private static final Identifier RELOAD_TEST = id("coverage_unchanged_antenna_load");
    private static final Identifier RADIO_CONFIG_TEST = id("coverage_radio_configuration");
    private static final Identifier STRUCTURE = id("coverage_test_space");
    private static final TelecomFrequency FREQUENCY = TelecomFrequency.G2_900;

    @SubscribeEvent
    public static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry -> {
            registry.register(TEST, CoverageWorldGameTests::wallRemoval);
            registry.register(RELOAD_TEST, CoverageWorldGameTests::unchangedAntennaLoad);
            registry.register(RADIO_CONFIG_TEST, CoverageWorldGameTests::radioConfiguration);
        });
    }

    @SubscribeEvent
    public static void registerTests(RegisterGameTestsEvent event) {
        // A separate batch prevents topology mutations in other tests from invalidating this sequence.
        var environment = event.registerEnvironment(id("coverage_test_environment"));
        event.registerTest(TEST, data -> new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, TEST), data),
                new TestData<>(environment, STRUCTURE, 200, 0, true));
        var reloadEnvironment = event.registerEnvironment(id("coverage_reload_environment"));
        event.registerTest(RELOAD_TEST, data -> new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, RELOAD_TEST), data),
                new TestData<>(reloadEnvironment, STRUCTURE, 200, 0, true));
        var radioEnvironment = event.registerEnvironment(id("coverage_radio_environment"));
        event.registerTest(RADIO_CONFIG_TEST, data -> new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, RADIO_CONFIG_TEST), data),
                new TestData<>(radioEnvironment, STRUCTURE, 200, 0, true));
    }

    @SubscribeEvent
    public static void installStructure(ServerAboutToStartEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) return;
        CompoundTag structure = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(32));
        size.add(IntTag.valueOf(6));
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

    private static void wallRemoval(GameTestHelper helper) {
        var level = helper.getLevel();
        helper.assertTrue(level.getServer() instanceof GameTestServer, "Requires isolated GameTestServer");
        helper.assertTrue(level.getServer().isSameThread(), "World mutations require the server thread");
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        // Align a real coverage cell inside the structure regardless of the runner's placement.
        BlockPos receiverRelative = new BlockPos(8 + Math.floorMod(-origin.getX(), 16), 2,
                8 + Math.floorMod(-origin.getZ(), 16));
        BlockPos sourceRelative = receiverRelative.west(6);
        BlockPos wallRelative = receiverRelative.west(3);
        BlockPos receiver = helper.absolutePos(receiverRelative);
        BlockPos source = helper.absolutePos(sourceRelative);
        var request = new CoverageService.Request(Math.floorDiv(receiver.getX(), 128),
                Math.floorDiv(receiver.getZ(), 128), 16, Integer.toString(receiver.getY()),
                Long.toString(source.asLong()), FREQUENCY.getTechnology(), FREQUENCY.name());
        AtomicReference<JsonObject> air = new AtomicReference<>();
        AtomicReference<JsonObject> wall = new AtomicReference<>();
        helper.setBlock(sourceRelative, ModBlocks.ANTENNA.get());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TelecomNetworkGraph.get(level).getNode(source) != null,
                        "Placed antenna must enter the actual graph"))
                .thenExecute(() -> helper.getBlockEntity(sourceRelative, AntennaBlockEntity.class)
                        .setEnabledFrequenciesMask(1 << FREQUENCY.ordinal()))
                .thenIdle(5)
                .thenWaitUntil(() -> air.set(ready(helper, request)))
                .thenExecute(() -> {
                    assertPointMatchesPropagator(helper, air.get(), source, receiver);
                    setStrict(helper, wallRelative, "minecraft:stone");
                    helper.assertBlockPresent(Blocks.STONE, wallRelative);
                    assertInvalidated(helper, request, air.get());
                })
                .thenWaitUntil(() -> wall.set(ready(helper, request)))
                .thenExecute(() -> {
                    assertPointMatchesPropagator(helper, wall.get(), source, receiver);
                    helper.assertTrue(cell(wall.get(), receiver).get("powerDbm").getAsFloat()
                                    < cell(air.get(), receiver).get("powerDbm").getAsFloat() - 1,
                            "A real stone wall must attenuate the map signal");
                    setStrict(helper, wallRelative, "minecraft:air");
                    helper.assertBlockPresent(Blocks.AIR, wallRelative);
                    assertInvalidated(helper, request, wall.get());
                })
                .thenWaitUntil(() -> {
                    JsonObject restored = ready(helper, request);
                    assertPointMatchesPropagator(helper, restored, source, receiver);
                    helper.assertTrue(Math.abs(cell(restored, receiver).get("powerDbm").getAsFloat()
                                    - cell(air.get(), receiver).get("powerDbm").getAsFloat()) < 0.0001f,
                            "Removing the wall must restore the original map signal");
                })
                .thenExecute(() -> helper.destroyBlock(sourceRelative))
                .thenSucceed();
    }

    private static void unchangedAntennaLoad(GameTestHelper helper) {
        var level = helper.getLevel();
        helper.assertTrue(level.getServer() instanceof GameTestServer, "Requires isolated GameTestServer");
        BlockPos relative = new BlockPos(1, 2, 1);
        BlockPos source = helper.absolutePos(relative);
        // No actual chunk reload: this distant tile cannot depend on the antenna's terrain chunk.
        var request = new CoverageService.Request(Math.floorDiv(source.getX(), 128) + 64,
                Math.floorDiv(source.getZ(), 128), 64, Integer.toString(source.getY()),
                "all", "all", "all");
        AtomicReference<JsonObject> tile = new AtomicReference<>();
        AtomicReference<String> model = new AtomicReference<>();
        helper.setBlock(relative, ModBlocks.ANTENNA.get());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TelecomNetworkGraph.get(level).getNode(source) != null,
                        "Placed antenna must enter the actual graph"))
                .thenExecute(() -> helper.getBlockEntity(relative, AntennaBlockEntity.class)
                        .setEnabledFrequenciesMask(1 << FREQUENCY.ordinal()))
                .thenIdle(5)
                .thenWaitUntil(() -> tile.set(ready(helper, request)))
                .thenExecute(() -> {
                    model.set(CoverageService.modelRevision(level));
                    var graph = TelecomNetworkGraph.get(level);
                    var node = graph.getNode(source);
                    long topology = graph.getTopologyRevision();
                    var antenna = helper.getBlockEntity(relative, AntennaBlockEntity.class);
                    helper.assertValueEqual(node.getFrequenciesMask(), antenna.getEnabledFrequenciesMask(),
                            "The fixture must restore an unchanged nonzero frequency mask");
                    antenna.onLoad();
                    antenna.onLoad();
                    helper.assertTrue(graph.getNode(source) == node, "Unchanged onLoad must preserve node identity");
                    helper.assertValueEqual(graph.getTopologyRevision(), topology, "Unchanged topology revision");
                    helper.assertValueEqual(snapshot(helper, request).get("revision").getAsString(),
                            tile.get().get("revision").getAsString(), "onLoad must preserve the distant tile revision");
                    helper.assertValueEqual(CoverageService.modelRevision(level), model.get(), "Unchanged model revision");
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(snapshot(helper, request).get("revision").getAsString(),
                            tile.get().get("revision").getAsString(), "Subsequent ticks must preserve the distant tile");
                    helper.assertValueEqual(CoverageService.modelRevision(level), model.get(), "No delayed spectrum invalidation");
                    helper.destroyBlock(relative);
                })
                .thenSucceed();
    }

    private static void radioConfiguration(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos receiverRelative = new BlockPos(8 + Math.floorMod(-origin.getX(), 16), 2,
                8 + Math.floorMod(-origin.getZ(), 16));
        BlockPos sourceRelative = receiverRelative.west(6);
        BlockPos receiver = helper.absolutePos(receiverRelative);
        BlockPos source = helper.absolutePos(sourceRelative);
        var request = new CoverageService.Request(Math.floorDiv(receiver.getX(), 128),
                Math.floorDiv(receiver.getZ(), 128), 16, Integer.toString(receiver.getY()),
                Long.toString(source.asLong()), FREQUENCY.getTechnology(), FREQUENCY.name());
        AtomicReference<JsonObject> forward = new AtomicReference<>();
        helper.setBlock(sourceRelative, ModBlocks.ANTENNA.get());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(TelecomNetworkGraph.get(level).getNode(source) != null,
                        "Placed antenna must enter the actual graph"))
                .thenExecute(() -> {
                    var antenna = helper.getBlockEntity(sourceRelative, AntennaBlockEntity.class);
                    antenna.setEnabledFrequenciesMask(1 << FREQUENCY.ordinal());
                    antenna.setRadioConfig(new AntennaRadioConfig(1, 270, 0, 30, 100));
                })
                .thenIdle(5)
                .thenWaitUntil(() -> forward.set(ready(helper, request)))
                .thenExecute(() -> {
                    assertPointMatchesPropagator(helper, forward.get(), source, receiver);
                    var config = new AntennaRadioConfig(1, 90, 0, 30, 50);
                    helper.getBlockEntity(sourceRelative, AntennaBlockEntity.class).setRadioConfig(config);
                    helper.assertValueEqual(TelecomNetworkGraph.get(level).getNode(source).getRadioConfig(), config,
                            "Graph must receive the complete antenna configuration");
                    assertInvalidated(helper, request, forward.get());
                })
                .thenWaitUntil(() -> {
                    JsonObject backward = ready(helper, request);
                    assertPointMatchesPropagator(helper, backward, source, receiver);
                    helper.assertTrue(cell(backward, receiver).get("powerDbm").getAsFloat()
                                    < cell(forward.get(), receiver).get("powerDbm").getAsFloat() - 20,
                            "Rotating a real sector away must attenuate the map signal");
                })
                .thenExecute(() -> helper.destroyBlock(sourceRelative))
                .thenSucceed();
    }

    private static void setStrict(GameTestHelper helper, BlockPos relative, String block) {
        BlockPos pos = helper.absolutePos(relative);
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block + " strict");
    }

    private static JsonObject snapshot(GameTestHelper helper, CoverageService.Request request) {
        return JsonParser.parseString(CoverageService.request(helper.getLevel(), request, Long.MAX_VALUE)).getAsJsonObject();
    }

    private static JsonObject ready(GameTestHelper helper, CoverageService.Request request) {
        JsonObject tile = snapshot(helper, request);
        helper.assertTrue(tile.get("status").getAsString().equals("ready"), "Waiting for bounded server ticks to finish coverage");
        return tile;
    }

    private static void assertInvalidated(GameTestHelper helper, CoverageService.Request request, JsonObject previous) {
        JsonObject pending = snapshot(helper, request);
        helper.assertTrue(!pending.get("revision").equals(previous.get("revision")),
                "Strict block writes must invalidate immediately, without neighbor notification or cache expiry");
        helper.assertTrue(pending.get("status").getAsString().equals("pending"), "New terrain needs a new coverage job");
    }

    private static void assertPointMatchesPropagator(GameTestHelper helper, JsonObject tile, BlockPos source, BlockPos receiver) {
        JsonObject cell = cell(tile, receiver);
        var trace = new SignalPropagator.MultiTrace(source, receiver, java.util.List.of(FREQUENCY),
                TelecomNetworkGraph.get(helper.getLevel()).getNode(source).getRadioConfig());
        while (!trace.advance(helper.getLevel(), 256, Long.MAX_VALUE)) { }
        var direct = trace.results().getFirst();
        helper.assertTrue(direct.known, "The real source, receiver and wall chunks must be loaded");
        helper.assertTrue(cell.get("state").getAsString().equals("signal"), "Selected map point must receive signal");
        helper.assertTrue(cell.get("antenna").getAsString().equals(Long.toString(source.asLong())), "Selected antenna must match");
        helper.assertTrue(cell.get("band").getAsString().equals(FREQUENCY.name()), "Selected band must match");
        helper.assertTrue(Math.abs(cell.get("powerDbm").getAsFloat() - direct.powerDbm) < 0.0001f,
                "Progressive coverage and synchronous SignalPropagator must agree at the same world point");
    }

    private static JsonObject cell(JsonObject tile, BlockPos receiver) {
        for (var value : tile.getAsJsonArray("cells")) {
            JsonObject cell = value.getAsJsonObject();
            if (cell.get("x").getAsInt() == receiver.getX() && cell.get("y").getAsInt() == receiver.getY()
                    && cell.get("z").getAsInt() == receiver.getZ()) return cell;
        }
        throw new AssertionError("Coverage grid omitted the aligned receiver " + receiver);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(TelecomMod.MODID, path);
    }
}
