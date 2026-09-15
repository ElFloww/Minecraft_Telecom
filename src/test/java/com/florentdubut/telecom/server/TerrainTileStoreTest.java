package com.florentdubut.telecom.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class TerrainTileStoreTest {
    @TempDir
    Path directory;

    @Test
    void persistsImageAndIdentityAcrossInstances() throws Exception {
        Path world = directory.resolve("world/cache");
        TerrainTileStore store = new TerrainTileStore(world);
        byte[] png = png(0xff123456);
        assertArrayEquals(png, store.storeIfAbsent(12, 34, png));
        TerrainTileStore reopened = new TerrainTileStore(world);
        assertArrayEquals(png, reopened.read(12, 34));
        assertEquals(store.id(), reopened.id());
        assertEquals(store.id(), UUID.fromString(store.id()).toString());
        assertArrayEquals(png, Files.readAllBytes(world.resolve("0_1/12_34.png")));
        assertNoTemporaryFiles();
    }

    @Test
    void neverOverwritesAnExistingImage() throws Exception {
        TerrainTileStore store = new TerrainTileStore(directory);
        byte[] first = png(0xff123456);
        byte[] second = png(0xffabcdef);
        assertFalse(Arrays.equals(first, second));
        store.storeIfAbsent(0, 0, first);
        var modified = Files.getLastModifiedTime(tile(0, 0));
        assertArrayEquals(first, store.storeIfAbsent(0, 0, second));
        assertArrayEquals(first, new TerrainTileStore(directory).storeIfAbsent(0, 0, second));
        assertArrayEquals(first, store.storeIfAbsent(0, 0, new byte[0]));
        assertArrayEquals(first, store.read(0, 0));
        assertEquals(modified, Files.getLastModifiedTime(tile(0, 0)));
        assertNoTemporaryFiles();
    }

    @Test
    void separatesSignedCoordinatesRegionsAndWorlds() throws Exception {
        TerrainTileStore store = new TerrainTileStore(directory);
        byte[] first = png(0xff123456);
        byte[] second = png(0xffabcdef);
        for (int[] coordinate : new int[][]{{-1, -33}, {1, 33}, {-32, 31}, {32, -32},
                {-1_874_999, 1_874_999}, {1_874_999, -1_874_999}}) {
            store.storeIfAbsent(coordinate[0], coordinate[1], first);
            assertArrayEquals(first, store.read(coordinate[0], coordinate[1]));
            assertTrue(Files.isRegularFile(tile(coordinate[0], coordinate[1])));
        }
        assertTrue(Files.isRegularFile(directory.resolve("-1_-2/-1_-33.png")));
        TerrainTileStore otherWorld = new TerrainTileStore(directory.resolve("other-world"));
        assertNotEquals(store.id(), otherWorld.id());
        assertNull(otherWorld.read(-1, -33));
        otherWorld.storeIfAbsent(-1, -33, second);
        assertArrayEquals(first, store.read(-1, -33));
        assertArrayEquals(second, otherWorld.read(-1, -33));
    }

    @Test
    void missingTilesReturnNullWithoutCreatingRegions() throws Exception {
        TerrainTileStore store = new TerrainTileStore(directory);
        assertNull(store.read(-1, 3));
        assertFalse(Files.exists(tile(-1, 3).getParent()));
        store.storeIfAbsent(0, 0, png(0));
        assertNull(store.read(1, 0));
    }

    @Test
    void rejectsOutOfBoundsCoordinatesIncludingIntegerExtremes() throws Exception {
        TerrainTileStore store = new TerrainTileStore(directory);
        byte[] png = png(0);
        for (int value : new int[]{-1_875_000, 1_875_000, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> store.read(value, 0));
            assertThrows(IllegalArgumentException.class, () -> store.read(0, value));
            assertThrows(IllegalArgumentException.class, () -> store.storeIfAbsent(value, 0, png));
            assertThrows(IllegalArgumentException.class, () -> store.storeIfAbsent(0, value, png));
        }
        assertNoTemporaryFiles();
    }

    @Test
    void invalidFilesAreErrorsAndAreNeverDeletedOrReplaced() throws Exception {
        TerrainTileStore store = new TerrainTileStore(directory);
        byte[] valid = png(0xff123456);
        byte[] badSignature = valid.clone();
        badSignature[0] = 0;
        byte[] badCrc = valid.clone();
        badCrc[29] ^= 1;
        byte[] bomb = valid.clone();
        ByteBuffer.wrap(bomb).putInt(16, Integer.MAX_VALUE);
        updateCrc(bomb, 8);
        byte[] oversizedChunk = valid.clone();
        ByteBuffer.wrap(oversizedChunk).putInt(33, Integer.MAX_VALUE);
        byte[] unreadable = valid.clone();
        unreadable[41] = 0; // Invalid zlib header, but a valid PNG chunk checksum.
        updateCrc(unreadable, 33);
        byte[] wrongDimensions = png(32, 16, 0);
        byte[][] invalid = {new byte[0], new byte[]{1, 2, 3}, badSignature, badCrc, bomb,
                oversizedChunk, unreadable, wrongDimensions, Arrays.copyOf(valid, valid.length - 12)};
        Files.createDirectories(tile(0, 0).getParent());
        for (byte[] bytes : invalid) {
            Files.write(tile(0, 0), bytes);
            assertThrows(IOException.class, () -> store.read(0, 0));
            assertThrows(IOException.class, () -> store.storeIfAbsent(0, 0, valid));
            assertArrayEquals(bytes, Files.readAllBytes(tile(0, 0)));
            assertThrows(IOException.class, () -> store.storeIfAbsent(1, 0, bytes));
            assertFalse(Files.exists(tile(1, 0)));
        }
        assertNoTemporaryFiles();
    }

    @Test
    void enforcesReadAndWriteSizeLimits() throws Exception {
        TerrainTileStore store = new TerrainTileStore(directory);
        byte[] valid = png(0xff123456);
        byte[] maximum = paddedPng(valid, 16_384);
        assertArrayEquals(maximum, store.storeIfAbsent(0, 0, maximum));
        assertArrayEquals(maximum, store.read(0, 0));
        byte[] excessive = paddedPng(valid, 16_385);
        Files.write(tile(1, 0), excessive);
        assertThrows(IOException.class, () -> store.read(1, 0));
        assertThrows(IOException.class, () -> store.storeIfAbsent(1, 0, valid));
        assertArrayEquals(excessive, Files.readAllBytes(tile(1, 0)));
        assertThrows(IOException.class, () -> store.storeIfAbsent(2, 0, excessive));
        assertFalse(Files.exists(tile(2, 0)));
        assertNoTemporaryFiles();
    }

    @Test
    void concurrentInstancesPublishOneCompleteWinnerAndIdentity() throws Exception {
        int workers = 12;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        byte[][] candidates = new byte[workers][];
        for (int i = 0; i < workers; i++) candidates[i] = png(0xff000000 | i * 12345);
        record Result(String id, byte[] png) {}
        try (var executor = Executors.newFixedThreadPool(workers)) {
            ArrayList<Future<Result>> results = new ArrayList<>();
            for (byte[] candidate : candidates) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IOException("Start timed out");
                    TerrainTileStore store = new TerrainTileStore(directory);
                    byte[] before = store.read(-33, 4);
                    if (before != null) assertTrue(Arrays.stream(candidates).anyMatch(p -> Arrays.equals(p, before)));
                    return new Result(store.id(), store.storeIfAbsent(-33, 4, candidate));
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            Result winner = results.getFirst().get(10, TimeUnit.SECONDS);
            assertTrue(Arrays.stream(candidates).anyMatch(p -> Arrays.equals(p, winner.png())));
            for (Future<Result> future : results) {
                Result result = future.get(10, TimeUnit.SECONDS);
                assertEquals(winner.id(), result.id());
                assertArrayEquals(winner.png(), result.png());
            }
            assertArrayEquals(winner.png(), new TerrainTileStore(directory).read(-33, 4));
        }
        assertNoTemporaryFiles();
    }

    @Test
    void rejectsSymlinksWithoutReadingOrReplacingTheirTargets() throws Exception {
        TerrainTileStore store = new TerrainTileStore(directory);
        byte[] valid = png(0xff123456);
        Path outside = directory.resolve("outside.png");
        Files.write(outside, valid);
        Files.createDirectories(tile(0, 0).getParent());
        createSymlink(tile(0, 0), outside);
        assertThrows(IOException.class, () -> store.read(0, 0));
        assertThrows(IOException.class, () -> store.storeIfAbsent(0, 0, png(0)));
        assertTrue(Files.isSymbolicLink(tile(0, 0)));
        assertArrayEquals(valid, Files.readAllBytes(outside));
        createSymlink(tile(1, 0), directory.resolve("missing.png"));
        assertThrows(IOException.class, () -> store.read(1, 0));
        assertThrows(IOException.class, () -> store.storeIfAbsent(1, 0, valid));
        assertFalse(Files.exists(directory.resolve("missing.png")));
        Path outsideRegion = Files.createDirectory(directory.resolve("outside-region"));
        createSymlink(directory.resolve("1_0"), outsideRegion);
        assertThrows(IOException.class, () -> store.read(32, 0));
        assertThrows(IOException.class, () -> store.storeIfAbsent(32, 0, valid));
        assertFalse(Files.exists(outsideRegion.resolve("32_0.png")));
        createSymlink(directory.resolve("linked-store"), directory);
        assertThrows(IOException.class, () -> new TerrainTileStore(directory.resolve("linked-store")));
        assertNoTemporaryFiles();
    }

    @Test
    void invalidIdentityAndFilesystemEntriesRemainErrors() throws Exception {
        TerrainTileStore store = new TerrainTileStore(directory);
        Files.writeString(directory.resolve("store.id"), "not-a-uuid");
        assertThrows(IOException.class, () -> new TerrainTileStore(directory));
        assertEquals("not-a-uuid", Files.readString(directory.resolve("store.id")));
        Files.createDirectories(tile(0, 0));
        assertThrows(IOException.class, () -> store.read(0, 0));
        assertThrows(IOException.class, () -> store.storeIfAbsent(0, 0, png(0)));
        Files.writeString(directory.resolve("1_0"), "not a region");
        assertThrows(IOException.class, () -> store.read(32, 0));
        assertThrows(IOException.class, () -> store.storeIfAbsent(32, 0, png(0)));
        assertEquals("not a region", Files.readString(directory.resolve("1_0")));
        assertNoTemporaryFiles();
    }

    @Test
    void fallsBackOnlyWhenHardLinksAreUnsupported() throws Exception {
        TerrainTileStore store = new TerrainTileStore(directory);
        byte[] png = png(0xff123456);
        Throwable[] unsupported = {new UnsupportedOperationException("Hard links unavailable"),
                new FileSystemException("target", "temporary", "Operation not supported")};
        for (int i = 0; i < unsupported.length; i++) {
            int cx = i;
            try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
                files.when(() -> Files.createLink(eq(tile(cx, 0)), any(Path.class))).thenThrow(unsupported[i]);
                assertArrayEquals(png, store.storeIfAbsent(cx, 0, png));
            }
            assertArrayEquals(png, store.read(cx, 0));
            assertNoTemporaryFiles();
        }
    }

    @Test
    void publicationFailurePropagatesAndCleansTemporaryFiles() throws Exception {
        TerrainTileStore store = new TerrainTileStore(directory);
        byte[] png = png(0xff123456);
        IOException failure = new FileSystemException("target", "temporary", "No space left on device");
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createLink(eq(tile(0, 0)), any(Path.class))).thenThrow(failure);
            assertSame(failure, assertThrows(IOException.class, () -> store.storeIfAbsent(0, 0, png)));
        }
        assertNull(store.read(0, 0));
        assertNoTemporaryFiles();
        assertArrayEquals(png, store.storeIfAbsent(0, 0, png));
    }

    @Test
    void invalidConcurrentWinnerIsPreservedAndReported() throws Exception {
        TerrainTileStore store = new TerrainTileStore(directory);
        byte[] png = png(0xff123456);
        byte[] invalid = {1, 2, 3};
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createLink(eq(tile(0, 0)), any(Path.class))).thenAnswer(invocation -> {
                Files.write(tile(0, 0), invalid);
                throw new FileAlreadyExistsException(tile(0, 0).toString());
            });
            assertThrows(IOException.class, () -> store.storeIfAbsent(0, 0, png));
        }
        assertArrayEquals(invalid, Files.readAllBytes(tile(0, 0)));
        assertNoTemporaryFiles();
    }

    private Path tile(int cx, int cz) {
        return directory.resolve((cx >> 5) + "_" + (cz >> 5)).resolve(cx + "_" + cz + ".png");
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var paths = Files.walk(directory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static byte[] png(int color) throws IOException {
        return png(16, 16, color);
    }

    private static byte[] png(int width, int height, int color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) image.setRGB(x, z, color);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static byte[] paddedPng(byte[] original, int size) {
        int offset = original.length - 12;
        byte[] padded = Arrays.copyOf(original, size);
        int length = size - original.length - 12;
        ByteBuffer.wrap(padded).putInt(offset, length).putInt(offset + 4, 0x70614464); // paDd ancillary chunk
        Arrays.fill(padded, offset + 8, offset + 8 + length, (byte) 0);
        updateCrc(padded, offset);
        System.arraycopy(original, original.length - 12, padded, size - 12, 12);
        return padded;
    }

    private static void updateCrc(byte[] png, int offset) {
        int length = ByteBuffer.wrap(png).getInt(offset);
        CRC32 crc = new CRC32();
        crc.update(png, offset + 4, length + 4);
        ByteBuffer.wrap(png).putInt(offset + 8 + length, (int) crc.getValue());
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            assumeTrue(false, "Symbolic links unavailable: " + e.getMessage());
        }
    }
}
