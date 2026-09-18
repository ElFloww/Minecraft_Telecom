package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record NetworkMapResponsePayload(UUID viewId, String dimension, List<MapNodeData> nodes,
                                        List<MapMicrowaveData> microwaveLinks) implements CustomPacketPayload {
    public static final int MAX_NODES = 8192;
    public static final int MAX_LINKS = 128;
    public static final int MAX_TEXT_LENGTH = 256;

    public NetworkMapResponsePayload {
        java.util.Objects.requireNonNull(viewId);
        if (dimension == null || dimension.length() > RequestNetworkMapPayload.MAX_DIMENSION_LENGTH
                || nodes.size() > MAX_NODES || microwaveLinks.size() > MAX_LINKS) {
            throw new IllegalArgumentException("Map response exceeds limits");
        }
        nodes = List.copyOf(nodes);
        microwaveLinks = List.copyOf(microwaveLinks);
        for (var node : nodes) {
            checkText(node.type());
            checkText(node.ipAddress());
            checkText(node.extraInfo());
        }
    }

    private static void checkText(String value) {
        if (value != null && value.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Map text too long");
    }
    public static final Type<NetworkMapResponsePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TelecomMod.MODID, "network_map_response"));
    
    public static final StreamCodec<FriendlyByteBuf, NetworkMapResponsePayload> STREAM_CODEC = StreamCodec.ofMember(
        NetworkMapResponsePayload::write,
        NetworkMapResponsePayload::new
    );

    public NetworkMapResponsePayload(FriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readUtf(RequestNetworkMapPayload.MAX_DIMENSION_LENGTH), readNodes(buffer), readLinks(buffer));
    }

    private static List<MapNodeData> readNodes(FriendlyByteBuf buffer) {
        int size = readCount(buffer, MAX_NODES);
        List<MapNodeData> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new MapNodeData(
                buffer.readBlockPos(),
                buffer.readUtf(MAX_TEXT_LENGTH),
                buffer.readUtf(MAX_TEXT_LENGTH),
                buffer.readUtf(MAX_TEXT_LENGTH)
            ));
        }
        return list;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(viewId);
        buffer.writeUtf(dimension, RequestNetworkMapPayload.MAX_DIMENSION_LENGTH);
        buffer.writeInt(nodes.size());
        for (MapNodeData node : nodes) {
            buffer.writeBlockPos(node.pos());
            buffer.writeUtf(node.type(), MAX_TEXT_LENGTH);
            buffer.writeUtf(node.ipAddress() != null ? node.ipAddress() : "", MAX_TEXT_LENGTH);
            buffer.writeUtf(node.extraInfo() != null ? node.extraInfo() : "", MAX_TEXT_LENGTH);
        }
        buffer.writeInt(microwaveLinks.size());
        for (var link : microwaveLinks) link.write(buffer);
    }

    private static int readCount(FriendlyByteBuf buffer, int max) {
        int count = buffer.readInt();
        if (count < 0 || count > max) throw new IllegalArgumentException("Invalid map count");
        return count;
    }

    private static List<MapMicrowaveData> readLinks(FriendlyByteBuf buffer) {
        int count = readCount(buffer, MAX_LINKS);
        List<MapMicrowaveData> links = new ArrayList<>(count);
        for (int i = 0; i < count; i++) links.add(MapMicrowaveData.read(buffer));
        return links;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
