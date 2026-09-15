package com.florentdubut.telecom.server;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32;

final class TerrainTileStore {
    private static final int MAX_COORDINATE = 1_874_999;
    private static final int MAX_PNG_BYTES = 16_384;
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    // Also coordinates separate instances when the filesystem needs the move fallback.
    private static final Object PUBLICATION_LOCK = new Object();

    private final Path directory;
    private final String id;

    TerrainTileStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        requireDirectory(this.directory);
        Path identity = this.directory.resolve("store.id");
        byte[] bytes = readFile(identity, 36);
        if (bytes == null) {
            publishIfAbsent(identity, UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
            bytes = readFile(identity, 36);
        }
        if (bytes == null || bytes.length != 36) throw new IOException("Invalid terrain store identity");
        String value = new String(bytes, StandardCharsets.US_ASCII);
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid terrain store identity", e);
        }
        id = value;
    }

    String id() {
        return id;
    }

    Path directory() {
        return directory;
    }

    byte[] read(int cx, int cz) throws IOException {
        Path target = tilePath(cx, cz);
        requireDirectory(directory);
        BasicFileAttributes region = attributes(target.getParent());
        if (region == null) return null;
        if (!region.isDirectory()) throw new IOException("Invalid terrain region: " + target.getParent());
        byte[] bytes = readFile(target, MAX_PNG_BYTES);
        if (bytes != null) verifyPng(bytes);
        return bytes;
    }

    byte[] storeIfAbsent(int cx, int cz, byte[] png) throws IOException {
        byte[] existing = read(cx, cz);
        if (existing != null) return existing;
        if (png == null || png.length > MAX_PNG_BYTES) throw new IOException("Invalid terrain PNG size");
        byte[] bytes = png.clone();
        verifyPng(bytes);
        Path target = tilePath(cx, cz);
        Files.createDirectories(target.getParent());
        requireDirectory(target.getParent());
        publishIfAbsent(target, bytes);
        byte[] definitive = read(cx, cz);
        if (definitive == null) throw new IOException("Published terrain tile disappeared: " + target);
        return definitive;
    }

    private Path tilePath(int cx, int cz) {
        if (cx < -MAX_COORDINATE || cx > MAX_COORDINATE || cz < -MAX_COORDINATE || cz > MAX_COORDINATE) {
            throw new IllegalArgumentException("Chunk outside world bounds");
        }
        return directory.resolve((cx >> 5) + "_" + (cz >> 5)).resolve(cx + "_" + cz + ".png");
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private static void requireDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        if (attributes == null || !attributes.isDirectory()) throw new IOException("Invalid terrain directory: " + path);
    }

    private static byte[] readFile(Path path, int limit) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        if (attributes == null) return null;
        if (!attributes.isRegularFile() || attributes.size() > limit) {
            throw new IOException("Invalid terrain cache file: " + path);
        }
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Terrain cache file exceeds size limit: " + path);
            return bytes;
        }
    }

    private static void publishIfAbsent(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".terrain-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            synchronized (PUBLICATION_LOCK) {
                try {
                    try {
                        Files.createLink(target, temporary);
                    } catch (UnsupportedOperationException e) {
                        Files.move(temporary, target);
                    } catch (FileSystemException e) {
                        String reason = e.getReason() == null ? "" : e.getReason().toLowerCase(Locale.ROOT);
                        if (!reason.contains("not supported") && !reason.contains("unsupported")
                                && !reason.contains("does not support")) throw e;
                        // No ATOMIC_MOVE: its existing-target behavior may replace the winner.
                        Files.move(temporary, target);
                    }
                } catch (FileAlreadyExistsException e) {
                    // The caller reads and validates the winning file, including invalid entries.
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void verifyPng(byte[] png) throws IOException {
        if (png.length < 45 || png.length > MAX_PNG_BYTES
                || !Arrays.equals(PNG_SIGNATURE, Arrays.copyOf(png, PNG_SIGNATURE.length))) {
            throw new IOException("Invalid terrain PNG signature or size");
        }
        ByteBuffer buffer = ByteBuffer.wrap(png);
        if (buffer.getInt(8) != 13 || buffer.getInt(12) != 0x49484452
                || buffer.getInt(16) != 16 || buffer.getInt(20) != 16) {
            throw new IOException("Terrain PNG must have a 16x16 IHDR");
        }
        // Bound every chunk before handing it to ImageIO, which trusts declared chunk lengths.
        boolean ended = false;
        for (int offset = 8; offset < png.length;) {
            if (png.length - offset < 12) throw new IOException("Truncated terrain PNG chunk");
            int length = buffer.getInt(offset);
            int type = buffer.getInt(offset + 4);
            if (length < 0 || length > png.length - offset - 12 || (offset != 8 && type == 0x49484452)) {
                throw new IOException("Invalid terrain PNG chunk");
            }
            CRC32 crc = new CRC32();
            crc.update(png, offset + 4, length + 4);
            if ((int) crc.getValue() != buffer.getInt(offset + 8 + length)) {
                throw new IOException("Invalid terrain PNG checksum");
            }
            offset += length + 12;
            if (type == 0x49454e44) {
                if (length != 0 || offset != png.length) throw new IOException("Invalid terrain PNG end");
                ended = true;
            }
        }
        if (!ended) throw new IOException("Missing terrain PNG end");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("No terrain PNG decoder");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                var image = reader.read(0);
                if (image == null || image.getWidth() != 16 || image.getHeight() != 16) {
                    throw new IOException("Unreadable terrain PNG");
                }
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException e) {
            throw new IOException("Unreadable terrain PNG", e);
        }
    }
}
