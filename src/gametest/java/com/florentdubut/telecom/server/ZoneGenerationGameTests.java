package com.florentdubut.telecom.server;

import com.florentdubut.telecom.TelecomMod;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = TelecomMod.MODID)
public final class ZoneGenerationGameTests {
    private static final Identifier TEST = Identifier.fromNamespaceAndPath(TelecomMod.MODID, "zone_generation");

    @SubscribeEvent
    public static void functions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry -> registry.register(TEST, ZoneGenerationGameTests::generate));
    }

    @SubscribeEvent
    public static void tests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "zone_generation_environment"));
        event.registerTest(TEST, data -> new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, TEST), data),
                new TestData<>(environment, Identifier.fromNamespaceAndPath(TelecomMod.MODID, "coverage_test_space"), 2400, 0, true));
    }

    private static void generate(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        try {
            var store = new TerrainTileStore(server.getWorldPath(LevelResource.ROOT).resolve("zone-test-" + java.util.UUID.randomUUID()));
            var captures = new TerrainCaptureQueue(server, store, (x, z) -> { });
            var manager = new ZoneJobManager(server, captures);
            BlockPos target = helper.absolutePos(BlockPos.ZERO).offset(1024, 0, 1024);
            int cx = target.getX() >> 4, cz = target.getZ() >> 4;
            var forcedBefore = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(level.getChunkSource().getForceLoadedChunks());
            manager.start(new ZoneJobManager.Request("terrain", cx * 16, cz * 16, cx * 16 + 15, cz * 16 + 15,
                    16, "surface", "all", "all", "all"));
            helper.startSequence().thenWaitUntil(() -> {
                manager.tick();
                String state = JsonParser.parseString(manager.status()).getAsJsonObject().getAsJsonObject("job").get("state").getAsString();
                helper.assertTrue(state.equals("completed") || state.equals("failed"), "Waiting for generated terrain and its PNG");
            }).thenExecute(() -> {
                var result = JsonParser.parseString(manager.status()).getAsJsonObject().getAsJsonObject("job");
                manager.close();
                captures.close();
                helper.assertTrue(result.get("state").getAsString().equals("completed"), result.get("message").getAsString());
                try { helper.assertTrue(store.read(cx, cz) != null, "Generated chunk must have a persisted PNG"); }
                catch (java.io.IOException error) { helper.fail(error.getMessage()); }
                helper.assertTrue(forcedBefore.equals(level.getChunkSource().getForceLoadedChunks()), "Do not modify permanent forced chunks");
            }).thenSucceed();
        } catch (java.io.IOException error) {
            helper.fail(error.getMessage());
        }
    }
}
