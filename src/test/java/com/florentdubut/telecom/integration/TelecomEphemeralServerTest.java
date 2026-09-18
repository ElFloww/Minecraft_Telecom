package com.florentdubut.telecom.integration;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.block.entity.RouterBlockEntity;
import com.florentdubut.telecom.registry.ModBlocks;
import com.florentdubut.telecom.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.fml.ModList;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(EphemeralTestServerProvider.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(20)
class TelecomEphemeralServerTest {
    private static final BlockPos POS = new BlockPos(-12, 64, 8);
    private static String previousHttpEnabled;

    @BeforeAll
    static void startIsolatedServer() {
        // The provider owns a temporary directory, but the mod's HTTP listener is not needed here.
        previousHttpEnabled = System.setProperty("telecom.http.enabled", "false");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            assertInstanceOf(EphemeralTestServerProvider.JUnitServer.class,
                    EphemeralTestServerProvider.grabServer());
        });
    }

    @AfterAll
    static void restoreHttpConfiguration() {
        if (previousHttpEnabled == null) {
            System.clearProperty("telecom.http.enabled");
        } else {
            System.setProperty("telecom.http.enabled", previousHttpEnabled);
        }
    }

    @Test
    void startsModAndRegistersCommandAndCreativeIcon(MinecraftServer server) throws Exception {
        onServer(server, () -> {
            assertTrue(ModList.get().isLoaded(TelecomMod.MODID));
            assertTrue(server.isRunning());
            assertTrue(server.getTickCount() > 0);
            assertNotNull(server.getCommands().getDispatcher().getRoot()
                    .getChild("telecom").getChild("recalculate"));
            var diagnostics = server.getCommands().getDispatcher().getRoot()
                    .getChild("telecom").getChild("diagnostics");
            assertNotNull(diagnostics);
            for (String action : java.util.List.of("start", "stop", "status")) {
                assertNotNull(diagnostics.getChild(action));
            }
            assertTrue(TelecomMod.TELECOM_TAB.get().getIconItem().is(ModItems.SMARTPHONE.get()));
            // 21.11.42 deliberately loads no levels: this is not a world-placement test.
            assertNull(server.overworld());
            assertFalse(server.getAllLevels().iterator().hasNext());
            assertFalse(server.isDedicatedServer());
        });
    }

    @Test
    void constructsAndSerializesEveryRegisteredBlockEntityVariant(MinecraftServer server) throws Exception {
        onServer(server, () -> {
            Map<String, String> expectedTypes = Map.ofEntries(
                    Map.entry("copper_cable", "cable"), Map.entry("fiber_cable", "cable"),
                    Map.entry("medium_fiber_cable", "cable"), Map.entry("big_fiber_cable", "cable"),
                    Map.entry("router", "router"), Map.entry("router_lite", "router"),
                    Map.entry("router_max", "router"), Map.entry("router_pro", "router"),
                    Map.entry("server", "server"), Map.entry("antenna", "antenna"),
                    Map.entry("microwave_dish", "microwave_dish"),
                    Map.entry("nro", "telecom_hub"), Map.entry("nra", "telecom_hub"),
                    Map.entry("pm", "telecom_hub"), Map.entry("sr", "telecom_hub"));
            assertEquals(expectedTypes.size(), ModBlocks.BLOCKS.getEntries().size());
            for (var holder : ModBlocks.BLOCKS.getEntries()) {
                var block = holder.get();
                var id = BuiltInRegistries.BLOCK.getKey(block);
                assertEquals("telecom", id.getNamespace());
                assertTrue(expectedTypes.containsKey(id.getPath()), id.toString());
                assertEquals(id, BuiltInRegistries.ITEM.getKey(block.asItem()));
                assertFalse(block.asItem().getDefaultInstance().isEmpty());

                var state = block.defaultBlockState();
                var entity = assertInstanceOf(EntityBlock.class, block).newBlockEntity(POS, state);
                assertNotNull(entity, id.toString());
                assertTrue(entity.getType().isValid(state), id.toString());
                assertEquals(Identifier.fromNamespaceAndPath("telecom", expectedTypes.get(id.getPath())),
                        BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()));
                CompoundTag saved = entity.saveWithFullMetadata(server.registryAccess());
                BlockEntity restored = BlockEntity.loadStatic(POS, state, saved, server.registryAccess());
                assertNotNull(restored, id.toString());
                assertSame(entity.getType(), restored.getType());
                assertEquals(POS, restored.getBlockPos());
                assertEquals(saved, restored.saveWithFullMetadata(server.registryAccess()), id.toString());
            }
        });
    }

    @Test
    void routerReadsLegacyNbtAndPreservesSpeedtestResults(MinecraftServer server) throws Exception {
        onServer(server, () -> {
            CompoundTag legacy = new CompoundTag();
            legacy.putString("id", "telecom:router");
            legacy.putInt("LastDownBw", 8123);
            legacy.putInt("LastUpBw", 4567);
            legacy.putInt("LastPing", 23);
            CompoundTag persistent = new CompoundTag();
            persistent.putString("existingData", "preserved");
            legacy.put("NeoForgeData", persistent);

            var state = ModBlocks.ROUTER_PRO.get().defaultBlockState();
            var router = assertInstanceOf(RouterBlockEntity.class,
                    BlockEntity.loadStatic(POS, state, legacy, server.registryAccess()));
            assertEquals(8123, router.getLastDownBw());
            assertEquals(4567, router.getLastUpBw());
            assertEquals(23, router.getLastPing());
            assertEquals(10000, router.getConfiguredMaxDown());
            assertEquals(10000, router.getConfiguredMaxUp());

            router.setLastSpeedtestResults(7654, 3210, 17);
            CompoundTag saved = router.saveWithFullMetadata(server.registryAccess());
            assertInstanceOf(IntTag.class, saved.get("LastDownBw"));
            assertInstanceOf(IntTag.class, saved.get("LastUpBw"));
            assertInstanceOf(IntTag.class, saved.get("LastPing"));
            assertEquals(persistent, saved.get("NeoForgeData"));
            var restored = assertInstanceOf(RouterBlockEntity.class,
                    BlockEntity.loadStatic(POS, state, saved, server.registryAccess()));
            assertEquals(7654, restored.getLastDownBw());
            assertEquals(3210, restored.getLastUpBw());
            assertEquals(17, restored.getLastPing());
        });
    }

    @Test
    void antennaReadsLegacyNbtAndSynchronizesSameFields(MinecraftServer server) throws Exception {
        onServer(server, () -> {
            CompoundTag legacy = new CompoundTag();
            legacy.putString("id", "telecom:antenna");
            legacy.putString("antennaName", "Existing relay");
            legacy.putInt("enabledFrequenciesMask", 5);
            var state = ModBlocks.ANTENNA.get().defaultBlockState();
            var antenna = assertInstanceOf(AntennaBlockEntity.class,
                    BlockEntity.loadStatic(POS, state, legacy, server.registryAccess()));
            assertEquals("Existing relay", antenna.getAntennaName());
            assertEquals(5, antenna.getEnabledFrequenciesMask());

            antenna.setAntennaName("Configured relay");
            antenna.setEnabledFrequenciesMask(3);
            CompoundTag saved = antenna.saveWithFullMetadata(server.registryAccess());
            assertInstanceOf(StringTag.class, saved.get("antennaName"));
            assertInstanceOf(IntTag.class, saved.get("enabledFrequenciesMask"));
            var restored = assertInstanceOf(AntennaBlockEntity.class,
                    BlockEntity.loadStatic(POS, state, saved, server.registryAccess()));
            assertEquals("Configured relay", restored.getAntennaName());
            assertEquals(3, restored.getEnabledFrequenciesMask());

            var receivingEntity = new AntennaBlockEntity(POS, state);
            receivingEntity.handleUpdateTag(TagValueInput.create(ProblemReporter.DISCARDING,
                    server.registryAccess(), antenna.getUpdateTag(server.registryAccess())));
            assertEquals(antenna.getAntennaName(), receivingEntity.getAntennaName());
            assertEquals(antenna.getEnabledFrequenciesMask(), receivingEntity.getEnabledFrequenciesMask());
        });
    }

    @Test
    void missingLegacyFieldsRetainSafeDefaults(MinecraftServer server) throws Exception {
        onServer(server, () -> {
            var input = TagValueInput.create(ProblemReporter.DISCARDING,
                    server.registryAccess(), new CompoundTag());
            var router = new RouterBlockEntity(POS, ModBlocks.ROUTER_LITE.get().defaultBlockState());
            router.loadCustomOnly(input);
            assertEquals(0, router.getLastDownBw());
            assertEquals(0, router.getLastUpBw());
            assertEquals(0, router.getLastPing());
            assertEquals(1000, router.getConfiguredMaxDown());
            assertEquals(700, router.getConfiguredMaxUp());

            var antenna = new AntennaBlockEntity(POS, ModBlocks.ANTENNA.get().defaultBlockState());
            String generatedName = antenna.getAntennaName();
            antenna.loadCustomOnly(input);
            assertEquals(generatedName, antenna.getAntennaName());
            assertFalse(generatedName.isBlank());
            assertEquals(0, antenna.getEnabledFrequenciesMask());
        });
    }

    private static void onServer(MinecraftServer server, Runnable action) throws Exception {
        server.submit(() -> {
            assertTrue(server.isSameThread());
            action.run();
        }).get(10, TimeUnit.SECONDS);
    }
}
