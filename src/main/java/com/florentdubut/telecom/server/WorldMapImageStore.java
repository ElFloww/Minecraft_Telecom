package com.florentdubut.telecom.server;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

final class WorldMapImageStore implements AutoCloseable {
    private static final int MAX_DIMENSION = 2048;
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    // Enough for our RGBA encoder's worst case, without retaining arbitrary PNG ancillary data.
    private static final int MAX_PNG_BYTES = MAX_DIMENSION * MAX_DIMENSION * 4 + 65_536;
    private static final int MAX_COORDINATE = 1_874_999;
    private static final int MAX_DIRTY = 4096;
    private static final int FORMAT = 0x574d0001;

    public record Snapshot(byte[] png, String revision, int originX, int originZ, int blocksPerPixel,
                           int width, int height, boolean empty, long updatedAt) {}

    public static final class Pending extends RuntimeException {
        private Pending() {
            super("World map pending", null, false, false);
        }
    }

    private record Key(int x, int z) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int order = Integer.compare(x, other.x);
            return order == 0 ? Integer.compare(z, other.z) : order;
        }
    }

    private record Stamp(long size, String modified, String created, String fileKey) {}
    private record Bounds(int x, int z, int scale, int width, int height) {}

    private final TerrainTileStore store;
    private final Path directory;
    private final Object control = new Object();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "world-map-image");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Snapshot latest;
    private volatile RuntimeException failure;
    private volatile boolean closed;
    // Only the worker owns the source index. No decoded per-chunk images are retained.
    private TreeMap<Key, Stamp> sources = new TreeMap<>();
    private Set<Key> dirty = new HashSet<>();
    private boolean rebuild = true;
    private boolean scheduled;

    WorldMapImageStore(TerrainTileStore store) {
        this.store = store;
        directory = store.directory().resolve("_overview");
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        synchronized (control) {
            schedule(0);
        }
    }

    public Snapshot snapshot() throws Pending {
        if (closed) throw new IllegalStateException("World map store closed");
        RuntimeException error = failure;
        if (error != null) throw error;
        Snapshot result = latest;
        if (result == null) throw new Pending();
        return result;
    }

    public void invalidate(int cx, int cz) {
        if (cx < -MAX_COORDINATE || cx > MAX_COORDINATE || cz < -MAX_COORDINATE || cz > MAX_COORDINATE) {
            throw new IllegalArgumentException("Chunk outside world bounds");
        }
        synchronized (control) {
            if (closed) throw new IllegalStateException("World map store closed");
            if (failure != null) throw failure;
            if (!rebuild) {
                dirty.add(new Key(cx, cz));
                if (dirty.size() > MAX_DIRTY) {
                    dirty.clear();
                    rebuild = true;
                }
            }
            if (!scheduled) schedule(1000);
        }
    }

    private void schedule(long delay) {
        scheduled = true;
        worker.schedule(this::run, delay, TimeUnit.MILLISECONDS);
    }

    private void run() {
        Set<Key> batch;
        boolean full;
        synchronized (control) {
            if (closed) return;
            batch = dirty;
            dirty = new HashSet<>();
            full = rebuild;
            rebuild = false;
        }
        try {
            TreeMap<Key, Stamp> next = full ? scan() : new TreeMap<>(sources);
            if (!full) {
                for (Key key : batch) {
                    check();
                    next.put(key, stamp(key));
                }
            }
            Snapshot previous = latest;
            if (previous != null && next.equals(sources)) return;
            Bounds bounds = bounds(next);
            byte[] fingerprint = fingerprint(next);
            if (previous == null) {
                Snapshot cached = load(bounds, next.isEmpty(), fingerprint);
                if (cached != null) {
                    publish(cached);
                    sources = next;
                    return;
                }
            }
            boolean incremental = !full && previous != null && !previous.empty
                    && bounds.scale == previous.blocksPerPixel;
            TreeSet<Key> draw = new TreeSet<>();
            BufferedImage image = null;
            if (incremental) {
                try {
                    image = decode(previous.png, previous.width, previous.height);
                    if (bounds.x != previous.originX || bounds.z != previous.originZ
                            || bounds.width != previous.width || bounds.height != previous.height) {
                        BufferedImage expanded = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
                        // Same-scale origins are aligned: copy exact samples with row-sized scratch space,
                        // then release the old raster before encoding (at most two 16 MiB rasters here).
                        expanded.getRaster().setRect((previous.originX - bounds.x) / bounds.scale,
                                (previous.originZ - bounds.z) / bounds.scale, image.getRaster());
                        image = expanded;
                    }
                } catch (IOException | RuntimeException ignored) {
                    incremental = false;
                }
            }
            if (incremental) {
                for (Key key : batch) {
                    if (!next.get(key).equals(sources.get(key))) draw.add(key);
                }
                if (bounds.scale > 16) {
                    Set<Long> pixels = new HashSet<>();
                    for (Key key : draw) pixels.add(pixel(key, bounds));
                    Map<Long, Key> representatives = new TreeMap<>();
                    for (Key key : next.keySet()) {
                        if (pixels.contains(pixel(key, bounds))) representatives.put(pixel(key, bounds), key);
                    }
                    draw.addAll(representatives.values());
                }
            } else {
                image = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
                draw.addAll(next.keySet());
            }
            for (Key key : draw) {
                check();
                Stamp captured = next.get(key);
                if (!captured.equals(stamp(key))) throw new IOException("Terrain source changed: " + key);
                byte[] png = store.read(key.x, key.z);
                if (png == null || !captured.equals(stamp(key))) {
                    throw new IOException("Terrain source changed: " + key);
                }
                paint(image, decode(png, 16, 16), key, bounds);
            }
            check();
            write(image, bounds, next.isEmpty(), fingerprint);
            sources = next;
        } catch (Pending ignored) {
            // Shutdown only: new captures never cancel an in-progress, coherent publication.
        } catch (IOException | RuntimeException e) {
            if (!closed) {
                failure = e instanceof IOException io ? new UncheckedIOException("Cannot build world map", io)
                        : new IllegalStateException("Cannot build world map", e);
            }
            // Terminal until reopened, rather than retrying a corrupt immutable source every second.
        } finally {
            synchronized (control) {
                scheduled = false;
                if (!closed && failure == null && (rebuild || !dirty.isEmpty())) schedule(1000);
            }
        }
    }

    private TreeMap<Key, Stamp> scan() throws IOException {
        TreeMap<Key, Stamp> result = new TreeMap<>();
        if (!Files.isDirectory(store.directory(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid terrain directory");
        }
        // Deliberately only root -> region -> source metadata, never recursive derived-cache traversal.
        try (var regions = Files.newDirectoryStream(store.directory())) {
            for (Path region : regions) {
                check();
                Key coordinates = parse(region.getFileName().toString());
                if (coordinates == null) continue;
                if (!Files.isDirectory(region, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Invalid terrain region: " + region);
                }
                try (var files = Files.newDirectoryStream(region, "*.png")) {
                    for (Path file : files) {
                        check();
                        String name = file.getFileName().toString();
                        Key key = parse(name.substring(0, name.length() - 4));
                        if (key == null || (key.x >> 5) != coordinates.x || (key.z >> 5) != coordinates.z) continue;
                        if (Math.abs((long) key.x) > MAX_COORDINATE || Math.abs((long) key.z) > MAX_COORDINATE) {
                            throw new IOException("Terrain source outside world bounds: " + file);
                        }
                        result.put(key, stamp(key));
                    }
                }
            }
        }
        return result;
    }

    private static Key parse(String name) {
        String[] parts = name.split("_", -1);
        if (parts.length != 2) return null;
        try {
            Key key = new Key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            return name.equals(key.x + "_" + key.z) ? key : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Stamp stamp(Key key) throws IOException {
        Path region = store.directory().resolve((key.x >> 5) + "_" + (key.z >> 5));
        if (!Files.isDirectory(region, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid terrain region");
        Path path = region.resolve(key.x + "_" + key.z + ".png");
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() < 45 || attributes.size() > 16_384) {
            throw new IOException("Invalid terrain source: " + path);
        }
        return new Stamp(attributes.size(), attributes.lastModifiedTime().toString(),
                attributes.creationTime().toString(), String.valueOf(attributes.fileKey()));
    }

    private static Bounds bounds(TreeMap<Key, Stamp> sources) {
        if (sources.isEmpty()) return new Bounds(0, 0, 1, 1, 1);
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Key key : sources.keySet()) {
            minX = Math.min(minX, key.x * 16);
            minZ = Math.min(minZ, key.z * 16);
            maxX = Math.max(maxX, key.x * 16 + 16);
            maxZ = Math.max(maxZ, key.z * 16 + 16);
        }
        for (int scale = 1; ; scale *= 2) {
            int x = Math.floorDiv(minX, scale) * scale;
            int z = Math.floorDiv(minZ, scale) * scale;
            int width = (maxX - x + scale - 1) / scale;
            int height = (maxZ - z + scale - 1) / scale;
            if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) return new Bounds(x, z, scale, width, height);
        }
    }

    private byte[] fingerprint(TreeMap<Key, Stamp> sources) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                for (var entry : sources.entrySet()) {
                    check();
                    output.writeInt(entry.getKey().x);
                    output.writeInt(entry.getKey().z);
                    Stamp stamp = entry.getValue();
                    output.writeLong(stamp.size);
                    output.writeUTF(stamp.modified);
                    output.writeUTF(stamp.created);
                    output.writeUTF(stamp.fileKey);
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long pixel(Key key, Bounds bounds) {
        int x = (key.x * 16 - bounds.x) / bounds.scale;
        int z = (key.z * 16 - bounds.z) / bounds.scale;
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static void paint(BufferedImage image, BufferedImage tile, Key key, Bounds bounds) {
        int x = (key.x * 16 - bounds.x) / bounds.scale;
        int z = (key.z * 16 - bounds.z) / bounds.scale;
        int sample = Math.min(16, bounds.scale);
        // Above chunk scale, mark one known pixel, not fractional alpha that rounds to invisible.
        // Collisions use the last coordinate in sorted order, not a spatially faithful area average.
        // This overview approximation never changes the full-resolution immutable source PNGs.
        for (int tz = 0; tz < 16; tz += sample) {
            for (int tx = 0; tx < 16; tx += sample) {
                int a = 0, r = 0, g = 0, b = 0;
                for (int dz = 0; dz < sample; dz++) {
                    for (int dx = 0; dx < sample; dx++) {
                        int color = tile.getRGB(tx + dx, tz + dz);
                        int alpha = color >>> 24;
                        a += alpha;
                        r += ((color >>> 16) & 255) * alpha;
                        g += ((color >>> 8) & 255) * alpha;
                        b += (color & 255) * alpha;
                    }
                }
                int color = a == 0 ? 0 : (Math.max(1, a / (sample * sample)) << 24)
                        | ((r / a) << 16) | ((g / a) << 8) | (b / a);
                image.setRGB(x + tx / sample, z + tz / sample, color);
            }
        }
    }

    private Snapshot load(Bounds bounds, boolean empty, byte[] fingerprint) {
        Path path = directory.resolve("world.map");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (Files.size(path) > MAX_FILE_BYTES) return null;
            try (var input = new DataInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))) {
                if (input.readInt() != FORMAT || !input.readUTF().equals(store.id())) return null;
                if (!Arrays.equals(input.readNBytes(32), fingerprint)) return null;
                Bounds stored = new Bounds(input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt());
                if (!stored.equals(bounds) || input.readBoolean() != empty) return null;
                String revision = input.readUTF();
                if (!UUID.fromString(revision).toString().equals(revision)) return null;
                long updatedAt = input.readLong();
                int size = input.readInt();
                if (updatedAt <= 0 || size < 45 || size > MAX_PNG_BYTES) return null;
                byte[] png = input.readNBytes(size);
                if (png.length != size || input.read() != -1) return null;
                decode(png, bounds.width, bounds.height);
                return new Snapshot(png, revision, bounds.x, bounds.z, bounds.scale, bounds.width, bounds.height, empty, updatedAt);
            }
        } catch (IOException | RuntimeException ignored) {
            return null; // Only the derived cache is disposable; invalid source data fails the job.
        }
    }

    private void write(BufferedImage image, Bounds bounds, boolean empty, byte[] fingerprint) throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid overview directory");
        Path temporary = Files.createTempFile(directory, ".world-", ".tmp");
        try {
            String revision = UUID.randomUUID().toString();
            long updatedAt = System.currentTimeMillis();
            long start;
            int size;
            // Encode directly to the one publication file, avoiding full-size encoded buffer copies.
            try (var output = new FileImageOutputStream(temporary.toFile())) {
                output.writeInt(FORMAT);
                output.writeUTF(store.id());
                output.write(fingerprint);
                output.writeInt(bounds.x);
                output.writeInt(bounds.z);
                output.writeInt(bounds.scale);
                output.writeInt(bounds.width);
                output.writeInt(bounds.height);
                output.writeBoolean(empty);
                output.writeUTF(revision);
                output.writeLong(updatedAt);
                long lengthOffset = output.getStreamPosition();
                output.writeInt(0);
                start = output.getStreamPosition();
                var writers = ImageIO.getImageWritersByFormatName("png");
                if (!writers.hasNext()) throw new IOException("No PNG encoder");
                var writer = writers.next();
                try {
                    writer.setOutput(output);
                    writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
                } finally {
                    writer.dispose();
                }
                long end = output.getStreamPosition();
                if (end > MAX_FILE_BYTES || end - start > MAX_PNG_BYTES) throw new IOException("World map exceeds size limit");
                size = (int) (end - start);
                // PNG writers may flush their output, so patch the length using a separate channel below.
                output.flush();
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.position(lengthOffset);
                    ByteBuffer length = ByteBuffer.allocate(4).putInt(size).flip();
                    while (length.hasRemaining()) channel.write(length);
                    channel.force(true);
                }
            }
            check();
            byte[] png;
            try (var input = Files.newInputStream(temporary)) {
                input.skipNBytes(start);
                png = input.readNBytes(size);
                if (png.length != size) throw new IOException("Truncated world map publication");
            }
            check();
            Files.move(temporary, directory.resolve("world.map"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            publish(new Snapshot(png, revision, bounds.x, bounds.z, bounds.scale, bounds.width, bounds.height, empty, updatedAt));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void publish(Snapshot snapshot) {
        synchronized (control) {
            check();
            latest = snapshot;
        }
    }

    private static BufferedImage decode(byte[] png, int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || png.length < 45 || png.length > MAX_PNG_BYTES) throw new IOException("Invalid world map dimensions or size");
        ByteBuffer buffer = ByteBuffer.wrap(png);
        if (buffer.getLong(0) != 0x89504e470d0a1a0aL || buffer.getInt(8) != 13
                || buffer.getInt(12) != 0x49484452 || buffer.getInt(16) != width || buffer.getInt(20) != height) {
            throw new IOException("Invalid world map PNG header");
        }
        boolean ended = false;
        for (int offset = 8; offset < png.length;) {
            if (png.length - offset < 12) throw new IOException("Truncated world map PNG");
            int length = buffer.getInt(offset);
            int type = buffer.getInt(offset + 4);
            if (length < 0 || length > png.length - offset - 12 || (offset != 8 && type == 0x49484452)) {
                throw new IOException("Invalid world map PNG chunk");
            }
            CRC32 crc = new CRC32();
            crc.update(png, offset + 4, length + 4);
            if ((int) crc.getValue() != buffer.getInt(offset + 8 + length)) throw new IOException("Invalid world map PNG checksum");
            offset += length + 12;
            if (type == 0x49454e44) {
                if (length != 0 || offset != png.length) throw new IOException("Invalid world map PNG end");
                ended = true;
            }
        }
        if (!ended) throw new IOException("Missing world map PNG end");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("No PNG decoder");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != width || reader.getHeight(0) != height) throw new IOException("Invalid PNG dimensions");
                // Explicit ARGB destination also bounds a malicious 16-bit PNG to four decoded bytes/pixel.
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                var parameters = reader.getDefaultReadParam();
                parameters.setDestination(image);
                reader.read(0, parameters);
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private void check() {
        if (closed || Thread.currentThread().isInterrupted()) throw new Pending();
    }

    @Override
    public void close() {
        synchronized (control) {
            closed = true;
            dirty.clear();
            worker.shutdownNow();
            latest = null;
        }
        try {
            worker.awaitTermination(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
