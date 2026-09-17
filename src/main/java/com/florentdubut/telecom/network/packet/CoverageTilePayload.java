package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record CoverageTilePayload(UUID viewId, String dimension, String modelRevision, int tileX, int tileZ,
                                  int step, int level, String status, float progress, List<Cell> cells)
        implements CustomPacketPayload {
    public static final int MAX_CELLS = 256;
    public static final Type<CoverageTilePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(TelecomMod.MODID, "coverage_tile"));
    public static final StreamCodec<FriendlyByteBuf, CoverageTilePayload> STREAM_CODEC = StreamCodec.ofMember(
            CoverageTilePayload::write, CoverageTilePayload::new);

    public record Cell(int x, int y, int z, String state, float powerDbm, String technology,
                       String band, String antenna, String service) { }

    public CoverageTilePayload {
        if (cells.size() > MAX_CELLS) throw new IllegalArgumentException("Too many coverage cells");
        if (!Float.isFinite(progress) || progress < 0 || progress > 1) {
            throw new IllegalArgumentException("Invalid coverage progress");
        }
        cells = List.copyOf(cells);
    }

    public CoverageTilePayload(FriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readUtf(256), buffer.readUtf(128), buffer.readInt(), buffer.readInt(),
                buffer.readInt(), buffer.readInt(), buffer.readUtf(16), buffer.readFloat(), readCells(buffer));
    }

    /** Keep the selected results exactly as produced for the web map, without duplicating radio rules. */
    public static CoverageTilePayload fromSnapshot(RequestCoverageTilePayload request, String snapshot) {
        JsonObject data = JsonParser.parseString(snapshot).getAsJsonObject();
        List<Cell> cells = new ArrayList<>();
        for (var value : data.getAsJsonArray("cells")) {
            JsonObject cell = value.getAsJsonObject();
            cells.add(new Cell(cell.get("x").getAsInt(), cell.get("y").getAsInt(), cell.get("z").getAsInt(),
                    text(cell, "state"), cell.get("powerDbm").isJsonNull() ? -120 : cell.get("powerDbm").getAsFloat(),
                    text(cell, "technology"), text(cell, "band"), text(cell, "antenna"), text(cell, "service")));
        }
        return new CoverageTilePayload(request.viewId(), request.dimension(), text(data, "modelRevision"),
                request.tileX(), request.tileZ(), request.step(), request.level(), text(data, "status"),
                data.get("progress").getAsFloat(), cells);
    }

    public static CoverageTilePayload unavailable(RequestCoverageTilePayload request, String status) {
        return new CoverageTilePayload(request.viewId(), request.dimension(), "", request.tileX(), request.tileZ(),
                request.step(), request.level(), status, 0, List.of());
    }

    private static String text(JsonObject value, String key) {
        return value.get(key).isJsonNull() ? "" : value.get(key).getAsString();
    }

    private static List<Cell> readCells(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_CELLS) throw new IllegalArgumentException("Invalid coverage cell count");
        List<Cell> cells = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cells.add(new Cell(buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readUtf(16),
                    buffer.readFloat(), buffer.readUtf(8), buffer.readUtf(64), buffer.readUtf(24), buffer.readUtf(16)));
        }
        return cells;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(viewId);
        buffer.writeUtf(dimension, 256);
        buffer.writeUtf(modelRevision, 128);
        buffer.writeInt(tileX);
        buffer.writeInt(tileZ);
        buffer.writeInt(step);
        buffer.writeInt(level);
        buffer.writeUtf(status, 16);
        buffer.writeFloat(progress);
        buffer.writeVarInt(cells.size());
        for (Cell cell : cells) {
            buffer.writeInt(cell.x());
            buffer.writeInt(cell.y());
            buffer.writeInt(cell.z());
            buffer.writeUtf(cell.state(), 16);
            buffer.writeFloat(cell.powerDbm());
            buffer.writeUtf(cell.technology(), 8);
            buffer.writeUtf(cell.band(), 64);
            buffer.writeUtf(cell.antenna(), 24);
            buffer.writeUtf(cell.service(), 16);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
