package com.florentdubut.telecom.gametest;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.CableBlock;
import com.florentdubut.telecom.block.TelecomHubBlock;
import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.block.entity.RouterBlockEntity;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.NetworkEdge;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.registry.ModBlocks;
import net.minecraft.SharedConstants;
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
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@EventBusSubscriber(modid = TelecomMod.MODID)
public final class TelecomWorldGameTests {
    private static final Identifier EMPTY_STRUCTURE = id("network_test_space");
    private static final List<String> TESTS = List.of("cable_lifecycle", "block_variants", "network_disk_save", "reload_preserves_topology");

    @SubscribeEvent
    public static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry -> {
            registry.register(id("cable_lifecycle"), TelecomWorldGameTests::cableLifecycle);
            registry.register(id("block_variants"), TelecomWorldGameTests::blockVariants);
            registry.register(id("network_disk_save"), TelecomWorldGameTests::networkDiskSave);
            registry.register(id("reload_preserves_topology"), TelecomWorldGameTests::reloadPreservesTopology);
        });
    }

    @SubscribeEvent
    public static void registerTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(id("network_test_environment"));
        // Keep the identity regression in its own batch: other tests intentionally change topology.
        var reloadEnvironment = event.registerEnvironment(id("network_reload_environment"));
        for (String name : TESTS) {
            event.registerTest(id(name), data -> new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, id(name)), data),
                    new TestData<>(name.equals("reload_preserves_topology") ? reloadEnvironment : environment,
                            EMPTY_STRUCTURE, 200, 0, true));
        }
    }

    @SubscribeEvent
    public static void installEmptyStructure(ServerAboutToStartEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) return;
        // Install only in memory, before the runner looks up test dimensions.
        CompoundTag structure = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(16));
        size.add(IntTag.valueOf(4));
        size.add(IntTag.valueOf(16));
        structure.put("size", size);
        structure.put("blocks", new ListTag());
        structure.put("entities", new ListTag());
        ListTag palette = new ListTag();
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        structure.put("palette", palette);
        event.getServer().getStructureManager().getOrCreate(EMPTY_STRUCTURE)
                .load(event.getServer().registryAccess().lookupOrThrow(Registries.BLOCK), structure);
    }

    private static void cableLifecycle(GameTestHelper helper) {
        assertTestServer(helper);
        BlockPos server = new BlockPos(1, 1, 1);
        BlockPos cable = new BlockPos(2, 1, 1);
        BlockPos otherCable = new BlockPos(3, 1, 1);
        BlockPos router = new BlockPos(4, 1, 1);
        var graph = TelecomNetworkGraph.get(helper.getLevel());
        BlockPos serverPos = helper.absolutePos(server);
        BlockPos routerPos = helper.absolutePos(router);
        AtomicReference<NetworkNode> originalRouter = new AtomicReference<>();
        AtomicReference<String> routerIp = new AtomicReference<>();
        AtomicReference<String> serverIp = new AtomicReference<>();
        helper.setBlock(server, ModBlocks.SERVER.get());
        helper.setBlock(cable, ModBlocks.FIBER_CABLE.get());
        helper.setBlock(otherCable, ModBlocks.FIBER_CABLE.get());
        helper.setBlock(router, ModBlocks.ROUTER_LITE.get());

        // No direct onLoad/recalculate calls: placement and normal server ticks must do the work.
        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertNode(helper, graph, serverPos, NetworkNode.NodeType.SERVER);
                    assertNode(helper, graph, routerPos, NetworkNode.NodeType.ROUTER);
                    helper.assertTrue(graph.calculatePathStats(routerPos, serverPos) != null, "Placed fiber must connect router to server");
                })
                .thenExecute(() -> {
                    originalRouter.set(graph.getNode(routerPos));
                    routerIp.set(graph.getNode(routerPos).getIpAddress());
                    serverIp.set(graph.getNode(serverPos).getIpAddress());
                    helper.assertTrue(routerIp.get() != null && serverIp.get() != null
                            && !routerIp.get().equals(serverIp.get()), "Server and router must have distinct fixed addresses");
                    helper.destroyBlock(cable);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(graph.calculatePathStats(routerPos, serverPos) == null, "Breaking fiber must invalidate the cached path");
                    helper.assertTrue(graph.getNode(routerPos) == originalRouter.get(), "Cable updates must not replace the router node");
                    assertNode(helper, graph, serverPos, NetworkNode.NodeType.SERVER);
                    helper.assertValueEqual(graph.getNode(routerPos).getIpAddress(), routerIp.get(), "Disconnecting fiber must retain router IP");
                    helper.assertValueEqual(graph.getNode(serverPos).getIpAddress(), serverIp.get(), "Disconnecting fiber must retain server IP");
                })
                .thenExecute(() -> {
                    BlockPos pos = helper.absolutePos(cable);
                    var minecraftServer = helper.getLevel().getServer();
                    minecraftServer.getCommands().performPrefixedCommand(minecraftServer.createCommandSourceStack(),
                            "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " telecom:fiber_cable");
                    helper.assertBlockPresent(ModBlocks.FIBER_CABLE.get(), cable);
                })
                .thenWaitUntil(() -> helper.assertTrue(graph.calculatePathStats(routerPos, serverPos) != null,
                        "Repairing fiber must restore the path"))
                .thenExecute(() -> {
                    helper.assertValueEqual(graph.getNode(routerPos).getIpAddress(), routerIp.get(), "Reconnecting fiber must retain router IP");
                    helper.assertValueEqual(graph.getNode(serverPos).getIpAddress(), serverIp.get(), "Reconnecting fiber must retain server IP");
                    helper.destroyBlock(router);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(graph.getNode(routerPos) == null, "Destroyed router must stay removed after pending onLoad callbacks");
                    helper.assertTrue(helper.getLevel().getBlockEntity(routerPos) == null, "Destroyed router block entity must be removed");
                    helper.assertTrue(graph.getEdges().stream().noneMatch(edge -> edge.getNodeA().equals(routerPos)
                            || edge.getNodeB().equals(routerPos)), "Destroyed router must have no remaining edges");
                    helper.destroyBlock(server);
                    helper.destroyBlock(cable);
                    helper.destroyBlock(otherCable);
                })
                .thenWaitUntil(() -> helper.assertTrue(graph.getNode(serverPos) == null, "Destroyed server must be removed from graph"))
                .thenSucceed();
    }

    private static void blockVariants(GameTestHelper helper) {
        assertTestServer(helper);
        List<Block> blocks = List.of(ModBlocks.ROUTER.get(), ModBlocks.ROUTER_LITE.get(),
                ModBlocks.ROUTER_MAX.get(), ModBlocks.ROUTER_PRO.get(), ModBlocks.SERVER.get(),
                ModBlocks.ANTENNA.get(), ModBlocks.NRO_BLOCK.get(), ModBlocks.NRA_BLOCK.get(),
                ModBlocks.PM_BLOCK.get(), ModBlocks.SR_BLOCK.get(), ModBlocks.COPPER_CABLE.get(),
                ModBlocks.FIBER_CABLE.get(), ModBlocks.MEDIUM_FIBER_CABLE.get(), ModBlocks.BIG_FIBER_CABLE.get());
        List<NetworkNode.NodeType> types = List.of(NetworkNode.NodeType.ROUTER, NetworkNode.NodeType.ROUTER,
                NetworkNode.NodeType.ROUTER, NetworkNode.NodeType.ROUTER, NetworkNode.NodeType.SERVER,
                NetworkNode.NodeType.ANTENNA, NetworkNode.NodeType.NRO, NetworkNode.NodeType.NRA,
                NetworkNode.NodeType.PM, NetworkNode.NodeType.SR);
        var graph = TelecomNetworkGraph.get(helper.getLevel());
        for (int i = 0; i < blocks.size(); i++) helper.setBlock(variantPos(i), blocks.get(i));
        BlockPos transientRouter = new BlockPos(14, 1, 14);
        helper.setBlock(transientRouter, ModBlocks.ROUTER.get());
        helper.destroyBlock(transientRouter);

        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    for (int i = 0; i < blocks.size(); i++) {
                        BlockPos pos = helper.absolutePos(variantPos(i));
                        BlockEntity entity = helper.getLevel().getBlockEntity(pos);
                        helper.assertTrue(entity != null && entity.getType().isValid(entity.getBlockState()),
                                "Each block variant must create a valid block entity: " + i);
                        if (i < types.size()) assertNode(helper, graph, pos, types.get(i));
                        else helper.assertTrue(graph.getNode(pos) == null, "Cables must not become graph nodes");
                    }
                    helper.assertTrue(graph.getNode(helper.absolutePos(transientRouter)) == null,
                            "Placement and destruction in one tick must not leave a ghost node");
                    BlockPos hub = variantPos(8);
                    NetworkNode original = graph.getNode(helper.absolutePos(hub));
                    var hubState = helper.getBlockState(hub);
                    helper.setBlock(hub, hubState.setValue(TelecomHubBlock.FACING,
                            hubState.getValue(TelecomHubBlock.FACING).getOpposite()));
                    helper.assertTrue(graph.getNode(helper.absolutePos(hub)) == original,
                            "Changing facing must preserve the hub node");
                    for (int i = 0; i < blocks.size(); i++) helper.destroyBlock(variantPos(i));
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    for (int i = 0; i < blocks.size(); i++) {
                        BlockPos pos = helper.absolutePos(variantPos(i));
                        helper.assertTrue(graph.getNode(pos) == null, "Removed variant must leave no graph node: " + i);
                        helper.assertTrue(helper.getLevel().getBlockEntity(pos) == null, "Removed variant must leave no block entity: " + i);
                    }
                })
                .thenSucceed();
    }

    private static void networkDiskSave(GameTestHelper helper) {
        assertTestServer(helper);
        BlockPos server = new BlockPos(1, 1, 1);
        BlockPos cable = new BlockPos(2, 1, 1);
        BlockPos router = new BlockPos(3, 1, 1);
        BlockPos antenna = new BlockPos(6, 1, 1);
        var level = helper.getLevel();
        var graph = TelecomNetworkGraph.get(level);
        BlockPos serverPos = helper.absolutePos(server);
        BlockPos routerPos = helper.absolutePos(router);
        BlockPos antennaPos = helper.absolutePos(antenna);
        AtomicReference<CompletableFuture<?>> saved = new AtomicReference<>();
        AtomicReference<CompoundTag> routerNbt = new AtomicReference<>();
        AtomicReference<CompoundTag> antennaNbt = new AtomicReference<>();
        helper.setBlock(server, ModBlocks.SERVER.get());
        helper.setBlock(cable, ModBlocks.FIBER_CABLE.get());
        helper.setBlock(router, ModBlocks.ROUTER_PRO.get());
        helper.setBlock(antenna, ModBlocks.ANTENNA.get());

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(graph.calculatePathStats(routerPos, serverPos) != null, "Network must connect before saving");
                    assertNode(helper, graph, antennaPos, NetworkNode.NodeType.ANTENNA);
                })
                .thenExecute(() -> {
                    var routerEntity = helper.getBlockEntity(router, RouterBlockEntity.class);
                    routerEntity.setLastSpeedtestResults(8765, 4321, 19);
                    var antennaEntity = helper.getBlockEntity(antenna, AntennaBlockEntity.class);
                    antennaEntity.setAntennaName("Saved world relay");
                    antennaEntity.setEnabledFrequenciesMask(5);
                    helper.assertTrue(antennaEntity.getUpdatePacket() instanceof ClientboundBlockEntityDataPacket,
                            "A placed antenna must create its update packet");
                    routerNbt.set(routerEntity.saveWithFullMetadata(level.registryAccess()));
                    antennaNbt.set(antennaEntity.saveWithFullMetadata(level.registryAccess()));
                    graph.addCoverageRecord(routerPos, 4, 3);
                    saved.set(level.getDataStorage().scheduleSave());
                })
                .thenWaitUntil(() -> helper.assertTrue(saved.get().isDone(), "SavedData disk write must finish"))
                .thenExecute(() -> {
                    saved.get().join();
                    try {
                        CompoundTag disk = level.getDataStorage().readTagFromDisk(TelecomNetworkGraph.TYPE.id(),
                                TelecomNetworkGraph.TYPE.dataFixType(), SharedConstants.getCurrentVersion().dataVersion().version());
                        var restored = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, disk.get("data")).getOrThrow();
                        assertNode(helper, restored, routerPos, NetworkNode.NodeType.ROUTER);
                        assertNode(helper, restored, serverPos, NetworkNode.NodeType.SERVER);
                        helper.assertValueEqual(restored.getNode(routerPos).getIpAddress(), graph.getNode(routerPos).getIpAddress(), "Disk-loaded router must retain its fixed IP");
                        helper.assertValueEqual(restored.getNode(serverPos).getIpAddress(), graph.getNode(serverPos).getIpAddress(), "Disk-loaded server must retain its fixed IP");
                        helper.assertValueEqual(restored.getNode(routerPos).getCapacityDown(), 10000, "Saved router capacity");
                        helper.assertValueEqual(restored.getNode(antennaPos).getFrequenciesMask(), 5, "Saved antenna frequencies");
                        helper.assertTrue(restored.calculatePathStats(routerPos, serverPos) != null, "Disk-loaded graph must retain its path");
                        helper.assertValueEqual(restored.getRecordedCoverage().get(routerPos.asLong()), (4 << 8) | 3, "Saved coverage");
                    } catch (IOException e) {
                        helper.fail("Could not read saved telecom_network.dat: " + e.getMessage());
                    }
                    helper.destroyBlock(router);
                    helper.destroyBlock(antenna);
                    restoreEntity(helper, router, ModBlocks.ROUTER_PRO.get(), routerNbt.get());
                    restoreEntity(helper, antenna, ModBlocks.ANTENNA.get(), antennaNbt.get());
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    assertNode(helper, graph, routerPos, NetworkNode.NodeType.ROUTER);
                    assertNode(helper, graph, antennaPos, NetworkNode.NodeType.ANTENNA);
                    var restoredRouter = helper.getBlockEntity(router, RouterBlockEntity.class);
                    helper.assertValueEqual(restoredRouter.getLastDownBw(), 8765, "Restored download result");
                    helper.assertValueEqual(restoredRouter.getLastUpBw(), 4321, "Restored upload result");
                    helper.assertValueEqual(restoredRouter.getLastPing(), 19, "Restored ping");
                    helper.assertValueEqual(helper.getBlockEntity(antenna, AntennaBlockEntity.class).getAntennaName(),
                            "Saved world relay", "Restored antenna name");
                    helper.assertValueEqual(graph.getNode(antennaPos).getFrequenciesMask(), 5, "onLoad must restore antenna frequencies into graph");
                    helper.assertTrue(graph.calculatePathStats(routerPos, serverPos) != null, "Restored entities must reconnect via normal ticks");
                    helper.destroyBlock(router);
                    helper.destroyBlock(antenna);
                    helper.destroyBlock(cable);
                    helper.destroyBlock(server);
                })
                .thenSucceed();
    }

    private static void reloadPreservesTopology(GameTestHelper helper) {
        assertTestServer(helper);
        var level = helper.getLevel();
        var graph = TelecomNetworkGraph.get(level);
        BlockPos server = new BlockPos(1, 1, 1);
        BlockPos cable = new BlockPos(2, 1, 1);
        BlockPos router = new BlockPos(3, 1, 1);
        Map<BlockPos, Block> blocks = Map.ofEntries(
                Map.entry(server, ModBlocks.SERVER.get()),
                Map.entry(cable, ModBlocks.FIBER_CABLE.get()),
                Map.entry(router, ModBlocks.ROUTER_LITE.get()),
                Map.entry(new BlockPos(5, 1, 1), ModBlocks.ANTENNA.get()),
                Map.entry(new BlockPos(7, 1, 1), ModBlocks.NRO_BLOCK.get()),
                Map.entry(new BlockPos(9, 1, 1), ModBlocks.NRA_BLOCK.get()),
                Map.entry(new BlockPos(11, 1, 1), ModBlocks.PM_BLOCK.get()),
                Map.entry(new BlockPos(13, 1, 1), ModBlocks.SR_BLOCK.get()),
                Map.entry(new BlockPos(1, 1, 4), ModBlocks.COPPER_CABLE.get()),
                Map.entry(new BlockPos(4, 1, 4), ModBlocks.MEDIUM_FIBER_CABLE.get()),
                Map.entry(new BlockPos(7, 1, 4), ModBlocks.BIG_FIBER_CABLE.get()));
        Map<BlockPos, NetworkNode> nodes = new HashMap<>();
        Map<BlockPos, String> addresses = new HashMap<>();
        AtomicReference<List<NetworkEdge>> edges = new AtomicReference<>();
        Runnable assertUnchanged = () -> {
            for (var entry : nodes.entrySet()) {
                var node = graph.getNode(entry.getKey());
                helper.assertTrue(node == entry.getValue(), "Restoration must preserve node identity");
                helper.assertValueEqual(node.getIpAddress(), addresses.get(entry.getKey()), "Restoration must not reassign IPs");
                helper.assertValueEqual(node.getNetworkCidr(), "10.250.0.0/24", "Restoration must not reassign CIDRs");
            }
            helper.assertValueEqual(graph.getEdges().size(), edges.get().size(), "Restoration must preserve edge count");
            for (int i = 0; i < edges.get().size(); i++) {
                helper.assertTrue(graph.getEdges().get(i) == edges.get().get(i),
                        "Restoration must not rebuild global edges");
            }
        };
        blocks.forEach(helper::setBlock);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(graph.calculatePathStats(helper.absolutePos(router), helper.absolutePos(server)) != null,
                        "Network must connect before restoration"))
                .thenIdle(5)
                .thenExecute(() -> {
                    for (BlockPos relativePos : blocks.keySet()) {
                        var node = graph.getNode(helper.absolutePos(relativePos));
                        if (node != null) {
                            nodes.put(node.getPosition(), node);
                            // Deliberately differ from the allocator's output to catch needless reassignment.
                            node.setIpAddress("10.250.0." + nodes.size());
                            node.setNetworkCidr("10.250.0.0/24");
                            addresses.put(node.getPosition(), node.getIpAddress());
                        }
                        BlockEntity entity = level.getBlockEntity(helper.absolutePos(relativePos));
                        entity.onLoad();
                        entity.onLoad();
                    }
                    helper.assertValueEqual(nodes.size(), 7, "All node classes must participate in restoration");
                    edges.set(List.copyOf(graph.getEdges()));
                    helper.assertTrue(!edges.get().isEmpty(), "Regression requires actual edges");
                    NetworkNode routerNode = graph.getNode(helper.absolutePos(router));
                    RouterBlockEntity routerEntity = helper.getBlockEntity(router, RouterBlockEntity.class);
                    routerNode.setCapacityDown(999);
                    routerNode.setCapacityUp(1000);
                    routerNode.setCapacitySyncRequired(true);
                    graph.setDirty(false);
                    routerEntity.onLoad();
                    helper.assertValueEqual(routerNode.getCapacityDown(), 1000, "Lite download profile must be restored");
                    helper.assertValueEqual(routerNode.getCapacityUp(), 700, "Legacy Lite upload must be corrected to 700");
                    helper.assertTrue(graph.isDirty(), "Corrected capacities must be persisted");
                    helper.assertTrue(!routerNode.requiresCapacitySync(), "Legacy synchronization flag must be cleared");
                    assertUnchanged.run();
                    graph.setDirty(false);
                    routerEntity.onLoad();
                    helper.assertTrue(!graph.isDirty(), "Repeated onLoad must not invalidate an unchanged graph");
                    assertUnchanged.run();
                    routerNode.setCapacityDown(0);
                    routerNode.setCapacityUp(45);
                    routerEntity.onLoad();
                    helper.assertValueEqual(routerNode.getCapacityDown(), 0, "Model v1 zero download must be preserved");
                    helper.assertValueEqual(routerNode.getCapacityUp(), 45, "Model v1 custom upload must be preserved");
                    helper.assertTrue(!graph.isDirty(), "Custom capacities within hardware must not invalidate the graph");
                })
                .thenIdle(5)
                .thenExecute(assertUnchanged)
                .thenExecute(() -> {
                    for (BlockPos relativePos : blocks.keySet()) {
                        BlockPos pos = helper.absolutePos(relativePos);
                        BlockEntity entity = level.getBlockEntity(pos);
                        CompoundTag saved = entity.saveWithFullMetadata(level.registryAccess());
                        BlockEntity restored = BlockEntity.loadStatic(pos, entity.getBlockState(), saved, level.registryAccess());
                        helper.assertTrue(restored != null && restored != entity, "Restore a new instance from saved NBT");
                        // Recreate block entities without placement/removal side effects, as on chunk reload.
                        entity.onChunkUnloaded();
                        level.setBlockEntity(restored);
                    }
                    var cableState = helper.getBlockState(cable);
                    helper.setBlock(cable, cableState.setValue(CableBlock.UP, !cableState.getValue(CableBlock.UP)));
                })
                .thenIdle(5)
                .thenExecute(assertUnchanged)
                .thenExecute(() -> blocks.keySet().forEach(helper::destroyBlock))
                .thenSucceed();
    }

    private static void restoreEntity(GameTestHelper helper, BlockPos pos, Block block, CompoundTag saved) {
        helper.setBlock(pos, block);
        BlockEntity restored = BlockEntity.loadStatic(helper.absolutePos(pos), block.defaultBlockState(),
                saved, helper.getLevel().registryAccess());
        helper.assertTrue(restored != null, "Saved block entity must deserialize");
        helper.getLevel().setBlockEntity(restored);
    }

    private static void assertNode(GameTestHelper helper, TelecomNetworkGraph graph, BlockPos pos, NetworkNode.NodeType type) {
        NetworkNode node = graph.getNode(pos);
        helper.assertTrue(node != null && node.getType() == type, "Expected " + type + " node at " + pos);
    }

    private static void assertTestServer(GameTestHelper helper) {
        helper.assertTrue(helper.getLevel().getServer() instanceof GameTestServer, "Run only in the isolated GameTestServer");
        helper.assertTrue(helper.getLevel().getServer().isSameThread(), "World mutations must run on the server thread");
    }

    private static BlockPos variantPos(int index) {
        return new BlockPos(1 + 3 * (index % 4), 1, 1 + 3 * (index / 4));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(TelecomMod.MODID, path);
    }
}
