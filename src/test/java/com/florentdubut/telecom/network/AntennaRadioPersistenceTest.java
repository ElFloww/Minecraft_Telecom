package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AntennaRadioPersistenceTest {
    private static final BlockPos POS = new BlockPos(1, 64, 1);
    private static final AntennaRadioConfig CONFIG = new AntennaRadioConfig(2, 359, 12, 43, 50);

    @Test
    void graphRoundTripLegacyAndInvalidConfigPreserveWiredCapacities() {
        TelecomNetworkGraph graph = new TelecomNetworkGraph();
        NetworkNode node = new NetworkNode(POS, NetworkNode.NodeType.ANTENNA);
        assertEquals(AntennaRadioConfig.DEFAULT, node.getRadioConfig());
        assertThrows(NullPointerException.class, () -> node.setRadioConfig(null));
        node.setRadioConfig(CONFIG);
        node.setFrequenciesMask(123);
        node.setCapacityDown(1234);
        node.setCapacityUp(567);
        graph.addNode(node);
        CompoundTag saved = (CompoundTag) TelecomNetworkGraph.CODEC.encodeStart(NbtOps.INSTANCE, graph).getOrThrow();
        NetworkNode restored = TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow().getNode(POS);
        assertEquals(CONFIG, restored.getRadioConfig());
        assertEquals(123, restored.getFrequenciesMask());
        assertEquals(1234, restored.getCapacityDown());
        assertEquals(567, restored.getCapacityUp());
        CompoundTag nodeTag = saved.getListOrEmpty("Nodes").getCompound(0).orElseThrow();
        for (String key : List.of("RadioSectors", "RadioAzimuth", "RadioDowntilt", "RadioPower", "RadioBandwidth")) {
            nodeTag.remove(key);
        }
        assertEquals(AntennaRadioConfig.DEFAULT,
                TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow().getNode(POS).getRadioConfig());
        CONFIG.writeTo(nodeTag);
        nodeTag.putInt("RadioAzimuth", -1);
        assertEquals(AntennaRadioConfig.DEFAULT,
                TelecomNetworkGraph.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow().getNode(POS).getRadioConfig());
    }

    @Test
    void entityRoundTripAndUpdateTagUseSameConfigurationAndLegacyDefaults() {
        AntennaBlockEntity entity = entity();
        assertEquals(AntennaRadioConfig.DEFAULT, entity.getRadioConfig());
        assertThrows(NullPointerException.class, () -> entity.setRadioConfig(null));
        entity.setRadioConfig(CONFIG);
        entity.setEnabledFrequenciesMask(5);
        entity.setAntennaName("Configured relay");
        CompoundTag saved = entity.saveCustomOnly(RegistryAccess.EMPTY);
        AntennaBlockEntity restored = entity();
        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, saved));
        assertEquals(CONFIG, restored.getRadioConfig());
        assertEquals(5, restored.getEnabledFrequenciesMask());
        assertEquals("Configured relay", restored.getAntennaName());
        AntennaBlockEntity client = entity();
        client.handleUpdateTag(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY,
                entity.getUpdateTag(RegistryAccess.EMPTY)));
        assertEquals(CONFIG, client.getRadioConfig());

        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, new CompoundTag()));
        assertEquals(AntennaRadioConfig.DEFAULT, restored.getRadioConfig());
        saved.putInt("RadioSectors", 4);
        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, saved));
        assertEquals(AntennaRadioConfig.DEFAULT, restored.getRadioConfig());
    }

    @Test
    void loadingEntitySynchronizesExistingGraphWithoutReplacingNodeOrWiredCaps() {
        var level = mock(ServerLevel.class);
        var graph = new TelecomNetworkGraph();
        var node = new NetworkNode(POS, NetworkNode.NodeType.ANTENNA);
        node.setCapacityDown(1234);
        node.setCapacityUp(567);
        graph.addNode(node);
        graph.setDirty(false);
        var entity = entity();
        entity.setRadioConfig(CONFIG);
        entity.setEnabledFrequenciesMask(5);
        entity.setLevel(level);
        try (var graphs = mockStatic(TelecomNetworkGraph.class); var coverage = mockStatic(CoverageService.class);
             var tracer = mockStatic(NetworkTracer.class)) {
            graphs.when(() -> TelecomNetworkGraph.get(level)).thenReturn(graph);
            entity.onLoad();
            assertSame(node, graph.getNode(POS));
            assertEquals(CONFIG, node.getRadioConfig());
            assertEquals(5, node.getFrequenciesMask());
            assertEquals(1234, node.getCapacityDown());
            assertEquals(567, node.getCapacityUp());
            assertTrue(graph.isDirty());
            graph.setDirty(false);
            entity.onLoad();
            assertFalse(graph.isDirty());
            coverage.verify(() -> CoverageService.invalidateAntennas(level), times(1));
            tracer.verifyNoInteractions();
        }
    }

    private static AntennaBlockEntity entity() {
        return new AntennaBlockEntity(POS, ModBlocks.ANTENNA.get().defaultBlockState());
    }
}
