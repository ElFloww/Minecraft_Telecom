package com.florentdubut.telecom.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class WorldMapImageStoreTest {
    @TempDir
    Path directory;

    @Test
    void emptyStorePublishesOneTransparentImageAndStableRevision() throws Exception {
        TerrainTileStore tiles = new TerrainTileStore(directory);
        try (var map = new WorldMapImageStore(tiles)) {
            var snapshot = await(map, value -> true);
            assertTrue(snapshot.empty());
            assertEquals(0, snapshot.originX());
            assertEquals(0, snapshot.originZ());
            assertEquals(1, snapshot.blocksPerPixel());
            assertEquals(1, snapshot.width());
            assertEquals(1, snapshot.height());
            assertEquals(0, image(snapshot).getRGB(0, 0));
            assertTrue(snapshot.updatedAt() > 0);
            assertEquals(snapshot.revision(), UUID.fromString(snapshot.revision()).toString());
            assertSame(snapshot, map.snapshot());
            assertOneMapFile();
        }
    }

    @Test
    void negativeBoundsUseWorldCoordinatesAndPreserveUnknownPixels() throws Exception {
        TerrainTileStore tiles = new TerrainTileStore(directory);
        tiles.storeIfAbsent(-2, -3, png(0xff123456));
        tiles.storeIfAbsent(0, -1, png(0xffabcdef));
        try (var map = new WorldMapImageStore(tiles)) {
            var snapshot = await(map, value -> true);
            assertFalse(snapshot.empty());
            assertEquals(-32, snapshot.originX());
            assertEquals(-48, snapshot.originZ());
            assertEquals(1, snapshot.blocksPerPixel());
            assertEquals(48, snapshot.width());
            assertEquals(48, snapshot.height());
            BufferedImage image = image(snapshot);
            assertEquals(0xff123456, image.getRGB(0, 0));
            assertEquals(0xff123456, image.getRGB(15, 15));
            assertEquals(0xffabcdef, image.getRGB(32, 32));
            assertEquals(0xffabcdef, image.getRGB(47, 47));
            assertEquals(0, image.getRGB(20, 20));
        }
    }

    @Test
    void expansionCapsBothDimensionsAndKeepsDistantChunksInOneImage() throws Exception {
        TerrainTileStore tiles = new TerrainTileStore(directory);
        byte[] original = png(0xff234567);
        tiles.storeIfAbsent(-1, -1, original);
        Path source = tile(-1, -1);
        var modified = Files.getLastModifiedTime(source);
        try (var map = new WorldMapImageStore(tiles)) {
            var first = await(map, value -> true);
            int distance = 1_874_999;
            int[][] corners = {{-distance, -distance}, {distance, -distance}, {-distance, distance}, {distance, distance}};
            int[] colors = {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffff00};
            for (int i = 0; i < corners.length; i++) {
                tiles.storeIfAbsent(corners[i][0], corners[i][1], png(colors[i]));
                map.invalidate(corners[i][0], corners[i][1]);
            }
            var expanded = await(map, value -> !value.revision().equals(first.revision()));
            int scale = expanded.blocksPerPixel();
            assertTrue(scale > 16);
            assertEquals(0, scale & (scale - 1));
            assertTrue(expanded.width() <= 2048);
            assertTrue(expanded.height() <= 2048);
            assertTrue((long) expanded.width() * expanded.height() * 4 <= 16 * 1024 * 1024);
            assertTrue(expanded.originX() <= -distance * 16);
            assertTrue((long) expanded.originX() + (long) expanded.width() * scale >= distance * 16L + 16);
            BufferedImage image = image(expanded);
            for (int i = 0; i < corners.length; i++) {
                assertEquals(colors[i], colorAt(image, expanded, corners[i][0] * 16, corners[i][1] * 16));
            }
            assertEquals(0xff234567, colorAt(image, expanded, -16, -16));
            assertArrayEquals(original, Files.readAllBytes(source));
            assertEquals(modified, Files.getLastModifiedTime(source));
            assertOneMapFile();
        }
    }

    @Test
    void stableBoundsOnlyReadNewAreasAndUnchangedSignalsDoNotPublish() throws Exception {
        TerrainTileStore tiles = spy(new TerrainTileStore(directory));
        tiles.storeIfAbsent(0, 0, png(0xffff0000));
        tiles.storeIfAbsent(3, 0, png(0xff0000ff));
        try (var map = new WorldMapImageStore(tiles)) {
            var first = await(map, value -> true);
            tiles.storeIfAbsent(1, 0, png(0xff00ff00));
            clearInvocations(tiles);
            map.invalidate(1, 0);
            var second = await(map, value -> !value.revision().equals(first.revision()));
            assertEquals(first.originX(), second.originX());
            assertEquals(first.width(), second.width());
            assertEquals(0xffff0000, image(second).getRGB(0, 0));
            assertEquals(0xff00ff00, image(second).getRGB(16, 0));
            assertEquals(0xff0000ff, image(second).getRGB(48, 0));
            verify(tiles, times(1)).read(1, 0);
            verify(tiles, never()).read(0, 0);
            verify(tiles, never()).read(3, 0);
            awaitIdle(map);
            long completed = executor(map).getCompletedTaskCount();
            var diskModified = Files.getLastModifiedTime(cache());
            clearInvocations(tiles);
            for (int i = 0; i < 100; i++) map.invalidate(1, 0);
            awaitCompleted(map, completed + 1);
            assertSame(second, map.snapshot());
            assertEquals(diskModified, Files.getLastModifiedTime(cache()));
            verify(tiles, never()).read(anyInt(), anyInt());
        }
    }

    @Test
    void scaleChangeRebuildsFromOriginalSources() throws Exception {
        TerrainTileStore tiles = spy(new TerrainTileStore(directory));
        tiles.storeIfAbsent(0, 0, png(0xffff0000));
        try (var map = new WorldMapImageStore(tiles)) {
            var first = await(map, value -> true);
            tiles.storeIfAbsent(128, 0, png(0xff0000ff));
            clearInvocations(tiles);
            map.invalidate(128, 0);
            var second = await(map, value -> !value.revision().equals(first.revision()));
            assertEquals(2, second.blocksPerPixel());
            assertEquals(1032, second.width());
            verify(tiles).read(0, 0);
            verify(tiles).read(128, 0);
            assertEquals(0xffff0000, image(second).getRGB(0, 0));
            assertEquals(0xff0000ff, image(second).getRGB(1031, 7));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 128, 4096})
    void sameScaleExpansionCopiesPixelsWithoutRereadingExistingSources(int span) throws Exception {
        TerrainTileStore tiles = spy(new TerrainTileStore(directory));
        BufferedImage pattern = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) pattern.setRGB(x, z, ((x * 16 + z) << 24) | (x << 16) | (z << 8) | 0x7f);
        }
        var encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(pattern, "png", encoded));
        byte[] original = encoded.toByteArray();
        byte[] distant = png(0xff0000ff);
        tiles.storeIfAbsent(0, 0, original);
        tiles.storeIfAbsent(span, span, distant);
        var originalModified = Files.getLastModifiedTime(tile(0, 0));
        var distantModified = Files.getLastModifiedTime(tile(span, span));
        try (var map = new WorldMapImageStore(tiles)) {
            var first = await(map, value -> true);
            byte[] firstPng = first.png().clone();
            int scale = first.blocksPerPixel();
            assertEquals(span == 2 ? 1 : span == 128 ? 2 : 64, scale);
            tiles.storeIfAbsent(-4, -8, png(0xff00ff00));
            clearInvocations(tiles);
            map.invalidate(-4, -8);
            assertSame(first, map.snapshot());
            var negative = await(map, value -> !value.revision().equals(first.revision()));
            assertEquals(scale, negative.blocksPerPixel());
            assertEquals(-64, negative.originX());
            assertEquals(-128, negative.originZ());
            assertEquals(first.width() + 64 / scale, negative.width());
            assertEquals(first.height() + 128 / scale, negative.height());
            verify(tiles, times(1)).read(anyInt(), anyInt());
            verify(tiles).read(-4, -8);
            byte[] negativePng = negative.png().clone();

            tiles.storeIfAbsent(span + 4, span + 8, png(0xffffff00));
            clearInvocations(tiles);
            map.invalidate(span + 4, span + 8);
            assertSame(negative, map.snapshot());
            var expanded = await(map, value -> !value.revision().equals(negative.revision()));
            assertEquals(scale, expanded.blocksPerPixel());
            assertEquals(negative.originX(), expanded.originX());
            assertEquals(negative.originZ(), expanded.originZ());
            assertEquals(negative.width() + 64 / scale, expanded.width());
            assertEquals(negative.height() + 128 / scale, expanded.height());
            assertTrue(expanded.width() <= 2048 && expanded.height() <= 2048);
            assertTrue((long) expanded.width() * expanded.height() * 4 <= 16 * 1024 * 1024);
            verify(tiles, times(1)).read(anyInt(), anyInt());
            verify(tiles).read(span + 4, span + 8);

            BufferedImage before = image(first);
            BufferedImage after = image(expanded);
            int offsetX = (first.originX() - expanded.originX()) / scale;
            int offsetZ = (first.originZ() - expanded.originZ()) / scale;
            for (int z = 0; z < before.getHeight(); z++) {
                assertArrayEquals(before.getRGB(0, z, before.getWidth(), 1, null, 0, before.getWidth()),
                        after.getRGB(offsetX, z + offsetZ, before.getWidth(), 1, null, 0, before.getWidth()));
            }
            assertEquals(0xff00ff00, colorAt(after, expanded, -64, -128));
            assertEquals(0xffffff00, colorAt(after, expanded, (span + 4) * 16, (span + 8) * 16));
            assertArrayEquals(firstPng, first.png());
            assertArrayEquals(negativePng, negative.png());
            assertArrayEquals(original, Files.readAllBytes(tile(0, 0)));
            assertArrayEquals(distant, Files.readAllBytes(tile(span, span)));
            assertEquals(originalModified, Files.getLastModifiedTime(tile(0, 0)));
            assertEquals(distantModified, Files.getLastModifiedTime(tile(span, span)));
        }
    }

    @Test
    void coarsePixelCollisionsAreDeterministicAcrossIncrementalUpdatesAndRebuilds() throws Exception {
        TerrainTileStore tiles = spy(new TerrainTileStore(directory));
        tiles.storeIfAbsent(-2048, 0, png(0xff000000));
        tiles.storeIfAbsent(2048, 0, png(0xff000000));
        tiles.storeIfAbsent(1, 0, png(0xffff0000));
        WorldMapImageStore.Snapshot updated;
        try (var map = new WorldMapImageStore(tiles)) {
            var first = await(map, value -> true);
            assertTrue(first.blocksPerPixel() > 16);
            tiles.storeIfAbsent(0, 0, png(0xff0000ff));
            tiles.storeIfAbsent(-2052, -4, png(0xff00ff00));
            clearInvocations(tiles);
            map.invalidate(0, 0);
            map.invalidate(-2052, -4);
            updated = await(map, value -> !value.revision().equals(first.revision()));
            assertEquals(first.blocksPerPixel(), updated.blocksPerPixel());
            assertTrue(updated.originX() < first.originX());
            assertTrue(updated.originZ() < first.originZ());
            assertEquals(0xffff0000, colorAt(image(updated), updated, 0, 0));
            assertEquals(0xff00ff00, colorAt(image(updated), updated, -2052 * 16, -64));
            verify(tiles).read(0, 0);
            verify(tiles).read(1, 0);
            verify(tiles).read(-2052, -4);
            verify(tiles, never()).read(-2048, 0);
            verify(tiles, never()).read(2048, 0);
        }
        Files.delete(cache());
        try (var map = new WorldMapImageStore(tiles)) {
            var rebuilt = await(map, value -> true);
            assertArrayEquals(updated.png(), rebuilt.png());
            assertNotEquals(updated.revision(), rebuilt.revision());
        }
    }

    @Test
    void invalidPreviousImageFallsBackToOriginalsForIncrementalUpdate() throws Exception {
        TerrainTileStore tiles = spy(new TerrainTileStore(directory));
        tiles.storeIfAbsent(0, 0, png(0xffff0000));
        tiles.storeIfAbsent(2, 0, png(0xff0000ff));
        try (var map = new WorldMapImageStore(tiles)) {
            var first = await(map, value -> true);
            first.png()[0] = 0;
            tiles.storeIfAbsent(1, 0, png(0xff00ff00));
            clearInvocations(tiles);
            map.invalidate(1, 0);
            var rebuilt = await(map, value -> !value.revision().equals(first.revision()));
            verify(tiles).read(0, 0);
            verify(tiles).read(1, 0);
            verify(tiles).read(2, 0);
            assertEquals(0xffff0000, image(rebuilt).getRGB(0, 0));
            assertEquals(0xff00ff00, image(rebuilt).getRGB(16, 0));
            assertEquals(0xff0000ff, image(rebuilt).getRGB(32, 0));
        }
    }

    @Test
    void capturesDuringBuildDoNotCancelOrStarvePublication() throws Exception {
        TerrainTileStore tiles = spy(new TerrainTileStore(directory));
        tiles.storeIfAbsent(0, 0, png(0xffff0000));
        tiles.storeIfAbsent(3, 0, png(0xff0000ff));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var map = new WorldMapImageStore(tiles)) {
            var first = await(map, value -> true);
            tiles.storeIfAbsent(1, 0, png(0xff00ff00));
            doAnswer(invocation -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(tiles).read(1, 0);
            map.invalidate(1, 0);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            tiles.storeIfAbsent(2, 0, png(0xffffff00));
            for (int i = 0; i < 200; i++) map.invalidate(2, 0);
            assertSame(first, map.snapshot());
            assertEquals(0, executor(map).getQueue().size());
            release.countDown();
            var second = await(map, value -> !value.revision().equals(first.revision()));
            assertEquals(0xff00ff00, image(second).getRGB(16, 0));
            assertEquals(0, image(second).getRGB(32, 0));
            var third = await(map, value -> !value.revision().equals(second.revision()));
            assertEquals(0xffffff00, image(third).getRGB(32, 0));
            assertEquals(0xff00ff00, image(third).getRGB(16, 0));
        } finally {
            release.countDown();
        }
    }

    @Test
    void restartReusesSnapshotWithoutReadingBasePngAndCallerNeverDoesIo() throws Exception {
        TerrainTileStore tiles = new TerrainTileStore(directory);
        tiles.storeIfAbsent(-1, 2, png(0xffabcdef));
        WorldMapImageStore.Snapshot saved;
        try (var map = new WorldMapImageStore(tiles)) {
            saved = await(map, value -> true);
        }
        TerrainTileStore reopened = spy(new TerrainTileStore(directory));
        doThrow(new AssertionError("Cached restart must not read base PNG")).when(reopened).read(anyInt(), anyInt());
        WorldMapImageStore map;
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            map = new WorldMapImageStore(reopened);
            files.verifyNoInteractions();
        }
        try (map) {
            var loaded = await(map, value -> true);
            assertEquals(saved.revision(), loaded.revision());
            assertEquals(saved.updatedAt(), loaded.updatedAt());
            assertArrayEquals(saved.png(), loaded.png());
            try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
                for (int i = 0; i < 1000; i++) assertSame(loaded, map.snapshot());
                files.verifyNoInteractions();
            }
            verify(reopened, never()).read(anyInt(), anyInt());
        }
    }

    @Test
    void startupScansOnlyRootRegionsAndDetectsOfflineCaptures() throws Exception {
        TerrainTileStore tiles = new TerrainTileStore(directory);
        tiles.storeIfAbsent(0, 0, png(0xffff0000));
        String revision;
        try (var map = new WorldMapImageStore(tiles)) {
            revision = await(map, value -> true).revision();
        }
        Files.createDirectories(directory.resolve("_atlas/0_0"));
        Files.write(directory.resolve("_atlas/0_0/1_1.png"), new byte[]{1, 2, 3});
        Files.createDirectories(directory.resolve("0_0/nested"));
        Files.write(directory.resolve("0_0/nested/2_2.png"), new byte[]{1, 2, 3});
        tiles.storeIfAbsent(-40, -40, png(0xff0000ff));
        TerrainTileStore observed = spy(tiles);
        Thread caller = Thread.currentThread();
        doAnswer(invocation -> {
            assertNotSame(caller, Thread.currentThread());
            return invocation.callRealMethod();
        }).when(observed).read(anyInt(), anyInt());
        try (var map = new WorldMapImageStore(observed)) {
            var snapshot = await(map, value -> true);
            assertNotEquals(revision, snapshot.revision());
            assertEquals(-640, snapshot.originX());
            assertEquals(0xff0000ff, image(snapshot).getRGB(0, 0));
            assertEquals(0xffff0000, image(snapshot).getRGB(640, 640));
        }
    }

    @Test
    void invalidSourceIsTerminalErrorWithoutRetryOrSourceReplacement() throws Exception {
        TerrainTileStore tiles = spy(new TerrainTileStore(directory));
        byte[] invalid = png(0xffabcdef);
        invalid[29] ^= 1;
        Files.createDirectories(tile(0, 0).getParent());
        Files.write(tile(0, 0), invalid);
        try (var map = new WorldMapImageStore(tiles)) {
            awaitCompleted(map, 1);
            var error = assertThrows(UncheckedIOException.class, map::snapshot);
            for (int i = 0; i < 100; i++) assertSame(error, assertThrows(UncheckedIOException.class, map::snapshot));
            assertSame(error, assertThrows(UncheckedIOException.class, () -> map.invalidate(0, 0)));
            verify(tiles, times(1)).read(0, 0);
            assertTrue(executor(map).getQueue().isEmpty());
            assertArrayEquals(invalid, Files.readAllBytes(tile(0, 0)));
            assertFalse(Files.exists(cache()));
        }
    }

    @Test
    void corruptAndOversizedDerivedCachesRebuildWithoutChangingSources() throws Exception {
        TerrainTileStore tiles = spy(new TerrainTileStore(directory));
        byte[] source = png(0xffabcdef);
        tiles.storeIfAbsent(0, 0, source);
        String previous;
        try (var map = new WorldMapImageStore(tiles)) {
            previous = await(map, value -> true).revision();
        }
        for (int corruption = 0; corruption < 3; corruption++) {
            if (corruption == 0) {
                Files.write(cache(), new byte[]{1, 2, 3});
            } else if (corruption == 1) {
                try (var file = new RandomAccessFile(cache().toFile(), "rw")) {
                    file.setLength(32L * 1024 * 1024 + 1);
                }
            } else {
                byte[] bytes = Files.readAllBytes(cache());
                int pngOffset;
                try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                    input.readInt();
                    input.readUTF();
                    input.skipNBytes(32 + 20 + 1);
                    input.readUTF();
                    input.readLong();
                    input.readInt();
                    pngOffset = bytes.length - input.available();
                }
                ByteBuffer.wrap(bytes).putInt(pngOffset + 16, Integer.MAX_VALUE);
                Files.write(cache(), bytes);
            }
            clearInvocations(tiles);
            try (var map = new WorldMapImageStore(tiles)) {
                var rebuilt = await(map, value -> true);
                assertNotEquals(previous, rebuilt.revision());
                previous = rebuilt.revision();
                assertEquals(0xffabcdef, image(rebuilt).getRGB(0, 0));
                verify(tiles).read(0, 0);
                assertArrayEquals(source, Files.readAllBytes(tile(0, 0)));
                assertOneMapFile();
            }
        }
    }

    @Test
    void closeIsBoundedEvenWhenSourceReadIgnoresInterruption() throws Exception {
        TerrainTileStore tiles = spy(new TerrainTileStore(directory));
        tiles.storeIfAbsent(0, 0, png(0xffabcdef));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            boolean waiting = true;
            while (waiting) {
                try {
                    waiting = !release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a filesystem operation which cannot be interrupted.
                }
            }
            return invocation.callRealMethod();
        }).when(tiles).read(0, 0);
        var map = new WorldMapImageStore(tiles);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(WorldMapImageStore.Pending.class, map::snapshot);
            assertTimeoutPreemptively(Duration.ofSeconds(1), map::close);
            assertThrows(IllegalStateException.class, map::snapshot);
            assertThrows(IllegalStateException.class, () -> map.invalidate(0, 0));
        } finally {
            release.countDown();
            map.close();
        }
        assertTrue(executor(map).awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(cache()));
    }

    @Test
    void dirtyOverflowKeepsOneJobAndRebuildsAllCapturedSources() throws Exception {
        TerrainTileStore tiles = spy(new TerrainTileStore(directory));
        tiles.storeIfAbsent(0, 0, png(0xffabcdef));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(tiles).read(0, 0);
        try (var map = new WorldMapImageStore(tiles)) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            tiles.storeIfAbsent(4, 0, png(0xffff0000));
            // Unique notifications stress the bounded queue; the fallback scans actual persisted bases.
            for (int i = 1; i <= 5000; i++) map.invalidate(i, 0);
            var dirty = WorldMapImageStore.class.getDeclaredField("dirty");
            dirty.setAccessible(true);
            assertTrue(((Set<?>) dirty.get(map)).size() <= 4096);
            assertTrue(executor(map).getQueue().isEmpty());
            release.countDown();
            var first = await(map, value -> true);
            assertEquals(16, first.width());
            var second = await(map, value -> value.width() == 80);
            assertNotEquals(first.revision(), second.revision());
            assertEquals(0xffff0000, image(second).getRGB(64, 0));
        } finally {
            release.countDown();
        }
    }

    private Path tile(int cx, int cz) {
        return directory.resolve((cx >> 5) + "_" + (cz >> 5)).resolve(cx + "_" + cz + ".png");
    }

    private Path cache() {
        return directory.resolve("_overview/world.map");
    }

    private void assertOneMapFile() throws IOException {
        try (var files = Files.list(directory.resolve("_overview"))) {
            assertEquals(java.util.List.of(cache()), files.toList());
        }
        assertTrue(Files.size(cache()) <= 32L * 1024 * 1024);
    }

    private static int colorAt(BufferedImage image, WorldMapImageStore.Snapshot snapshot, int x, int z) {
        return image.getRGB((x - snapshot.originX()) / snapshot.blocksPerPixel(),
                (z - snapshot.originZ()) / snapshot.blocksPerPixel());
    }

    private static BufferedImage image(WorldMapImageStore.Snapshot snapshot) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(snapshot.png()));
    }

    private static byte[] png(int color) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) image.setRGB(x, z, color);
        }
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static WorldMapImageStore.Snapshot await(WorldMapImageStore map,
                                                    Predicate<WorldMapImageStore.Snapshot> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try {
                var snapshot = map.snapshot();
                if (condition.test(snapshot)) return snapshot;
            } catch (WorldMapImageStore.Pending ignored) {
                // Only pending is transient; build errors must fail the test immediately.
            }
            Thread.sleep(10);
        }
        throw new AssertionError("World map publication timed out");
    }

    private static ScheduledThreadPoolExecutor executor(WorldMapImageStore map) throws Exception {
        var field = WorldMapImageStore.class.getDeclaredField("worker");
        field.setAccessible(true);
        return (ScheduledThreadPoolExecutor) field.get(map);
    }

    private static void awaitIdle(WorldMapImageStore map) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor(map).getActiveCount() != 0 || !executor(map).getQueue().isEmpty()) {
            if (System.nanoTime() > deadline) fail("Worker did not become idle");
            Thread.sleep(10);
        }
    }

    private static void awaitCompleted(WorldMapImageStore map, long count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor(map).getCompletedTaskCount() < count) {
            if (System.nanoTime() > deadline) fail("Worker did not finish batch");
            Thread.sleep(10);
        }
    }
}
