package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.MicrowaveDishBlock;
import com.florentdubut.telecom.block.entity.MicrowaveDishBlockEntity;
import com.florentdubut.telecom.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.TagValueInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MicrowaveDishPersistenceTest {
    private static final BlockPos POS = new BlockPos(1, 64, 1);
    private static final MicrowaveConfig CONFIG = new MicrowaveConfig(POS.west(100), 16, 38, 90, 12, true);

    @Test
    void diskAndUpdateTagsRoundTripAndMissingOrInvalidDataUseDefaults() {
        var dish = dish();
        assertEquals(MicrowaveConfig.DEFAULT, dish.getConfig());
        assertThrows(NullPointerException.class, () -> dish.setConfig(null));
        dish.setConfig(CONFIG);
        dish.setDishName("Site A");
        var saved = dish.saveCustomOnly(RegistryAccess.EMPTY);
        var restored = dish();
        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, saved));
        assertEquals(CONFIG, restored.getConfig());
        assertEquals("Site A", restored.getDishName());
        var client = dish();
        client.handleUpdateTag(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, dish.getUpdateTag(RegistryAccess.EMPTY)));
        assertEquals(CONFIG, client.getConfig());
        assertEquals("Site A", client.getDishName());
        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, new CompoundTag()));
        assertEquals(MicrowaveConfig.DEFAULT, restored.getConfig());
        saved.putInt("MicrowaveChannel", 17);
        saved.putString("dishName", "x".repeat(33));
        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, saved));
        assertEquals(MicrowaveConfig.DEFAULT, restored.getConfig());
        assertEquals("FH", restored.getDishName());
        assertThrows(IllegalArgumentException.class, () -> dish.setDishName("x".repeat(33)));
        assertThrows(IllegalArgumentException.class, () -> dish.setDishName("line\nfeed"));
    }

    @Test
    void unchangedLoadDoesNotInvalidateOrRetraceAndUnloadDoesNotDestroyNode() {
        var level = mock(ServerLevel.class);
        var graph = new TelecomNetworkGraph();
        var node = new NetworkNode(POS, NetworkNode.NodeType.MICROWAVE_DISH);
        node.setMicrowaveConfig(CONFIG);
        node.setCapacityDown(1234);
        node.setCapacityUp(567);
        graph.addNode(node);
        graph.setDirty(false);
        var dish = dish();
        dish.setConfig(CONFIG);
        dish.setLevel(level);
        try (var graphs = mockStatic(TelecomNetworkGraph.class); var links = mockStatic(MicrowaveLinkService.class);
             var tracer = mockStatic(NetworkTracer.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            dish.onLoad();
            dish.setConfig(CONFIG);
            dish.onChunkUnloaded();
            dish.setRemoved();
            assertSame(node, graph.getNode(POS));
            assertEquals(1234, node.getCapacityDown());
            assertEquals(567, node.getCapacityUp());
            assertFalse(graph.isDirty());
            links.verifyNoInteractions();
            tracer.verifyNoInteractions();
        }
    }

    @Test
    void newNodeRetracesOnceAndChangedConfigMirrorsInvalidatesAndUpdatesFacing() {
        var level = mock(ServerLevel.class);
        var graph = new TelecomNetworkGraph();
        var dish = dish();
        dish.setLevel(level);
        try (var graphs = mockStatic(TelecomNetworkGraph.class); var links = mockStatic(MicrowaveLinkService.class);
             var tracer = mockStatic(NetworkTracer.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            dish.onLoad();
            assertEquals(NetworkNode.NodeType.MICROWAVE_DISH, graph.getNode(POS).getType());
            dish.onLoad();
            tracer.verify(() -> NetworkTracer.scheduleRecalculation(level, NetworkDiagnostics.Cause.NODE_LOAD), times(1));
            dish.setConfig(CONFIG);
            assertEquals(CONFIG, graph.getNode(POS).getMicrowaveConfig());
            assertTrue(graph.isDirty());
            links.verify(() -> MicrowaveLinkService.invalidateEndpoint(level, POS), atLeastOnce());
            verify(level).setBlock(POS, dish.getBlockState().setValue(MicrowaveDishBlock.FACING, Direction.WEST), Block.UPDATE_CLIENTS);
            tracer.verify(() -> NetworkTracer.scheduleRecalculation(level, NetworkDiagnostics.Cause.NODE_LOAD), times(1));
            dish.preRemoveSideEffects(POS, dish.getBlockState());
            assertNull(graph.getNode(POS));
            tracer.verify(() -> NetworkTracer.scheduleRecalculation(level, NetworkDiagnostics.Cause.NODE_REMOVE), times(1));
        }
    }

    @Test
    void changedRestoreUpdatesExistingNodeWithoutWiredRetrace() {
        var level = mock(ServerLevel.class);
        var graph = new TelecomNetworkGraph();
        var node = new NetworkNode(POS, NetworkNode.NodeType.MICROWAVE_DISH);
        graph.addNode(node);
        var dish = dish();
        dish.setConfig(CONFIG);
        dish.setLevel(level);
        try (var graphs = mockStatic(TelecomNetworkGraph.class); var links = mockStatic(MicrowaveLinkService.class);
             var tracer = mockStatic(NetworkTracer.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            dish.onLoad();
            assertSame(node, graph.getNode(POS));
            assertEquals(CONFIG, node.getMicrowaveConfig());
            links.verify(() -> MicrowaveLinkService.invalidateEndpoint(level, POS));
            tracer.verifyNoInteractions();
        }
    }

    private static MicrowaveDishBlockEntity dish() {
        return new MicrowaveDishBlockEntity(POS, ModBlocks.MICROWAVE_DISH.get().defaultBlockState());
    }
}
