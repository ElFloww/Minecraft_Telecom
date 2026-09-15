package com.florentdubut.telecom.network;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SavedDataProtectionTest {
    @TempDir
    Path directory;

    @Test
    void onlyMissingFilesCreateANewNetwork() throws Exception {
        try (var storage = new DimensionDataStorage(directory, null, RegistryAccess.EMPTY)) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(storage);
            assertTrue(graph.getNodes().isEmpty());
            assertSame(graph, TelecomNetworkGraph.get(storage));
            storage.saveAndJoin();
        }
        assertTrue(Files.exists(directory.resolve("telecom_network.dat")));
        try (var storage = new DimensionDataStorage(directory, null, RegistryAccess.EMPTY)) {
            assertNotNull(TelecomNetworkGraph.get(storage));
        }
    }

    @Test
    void aFutureSchemaCannotBeReplacedWithAnEmptyGraph() throws Exception {
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.putInt("SchemaVersion", 999);
        data.putString("importantFutureData", "must survive");
        root.put("data", data);
        Path file = directory.resolve("telecom_network.dat");
        NbtIo.writeCompressed(root, file);
        byte[] original = Files.readAllBytes(file);

        try (var storage = new DimensionDataStorage(directory, null, RegistryAccess.EMPTY)) {
            assertThrows(IllegalStateException.class, () -> TelecomNetworkGraph.get(storage));
            assertThrows(IllegalStateException.class, () -> TelecomNetworkGraph.get(storage));
            storage.saveAndJoin();
        }
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void aCorruptFileCannotBeReplacedWithAnEmptyGraph() throws Exception {
        Path file = directory.resolve("telecom_network.dat");
        byte[] original = {10, 0, 0, 127};
        Files.write(file, original);
        try (var storage = new DimensionDataStorage(directory, null, RegistryAccess.EMPTY)) {
            assertThrows(IllegalStateException.class, () -> TelecomNetworkGraph.get(storage));
            storage.saveAndJoin();
        }
        assertArrayEquals(original, Files.readAllBytes(file));
    }
}
