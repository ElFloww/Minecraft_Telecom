package com.florentdubut.telecom.network.packet;

import com.florentdubut.telecom.TelecomMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record NetworkMapResponsePayload(List<MapNodeData> nodes) implements CustomPacketPayload {
    public static final Type<NetworkMapResponsePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TelecomMod.MODID, "network_map_response"));
    
    public static final StreamCodec<FriendlyByteBuf, NetworkMapResponsePayload> STREAM_CODEC = StreamCodec.ofMember(
        NetworkMapResponsePayload::write,
        NetworkMapResponsePayload::new
    );

    public NetworkMapResponsePayload(FriendlyByteBuf buffer) {
        this(readNodes(buffer));
    }

    private static List<MapNodeData> readNodes(FriendlyByteBuf buffer) {
        int size = buffer.readInt();
        List<MapNodeData> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new MapNodeData(
                buffer.readBlockPos(),
                buffer.readUtf(),
                buffer.readUtf(),
                buffer.readUtf()
            ));
        }
        return list;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeInt(nodes.size());
        for (MapNodeData node : nodes) {
            buffer.writeBlockPos(node.pos());
            buffer.writeUtf(node.type());
            buffer.writeUtf(node.ipAddress() != null ? node.ipAddress() : "");
            buffer.writeUtf(node.extraInfo() != null ? node.extraInfo() : "");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
