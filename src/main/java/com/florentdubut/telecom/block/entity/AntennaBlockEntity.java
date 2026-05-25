package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.registry.ModBlockEntities;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;

public class AntennaBlockEntity extends BlockEntity {
    private String antennaName = "Relay-" + (int)(Math.random() * 10000);
    private boolean is3GEnabled = true;
    private boolean is4GEnabled = true;
    private boolean is5GEnabled = false;

    public AntennaBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ANTENNA_BE.get(), pos, state);
    }

    public void onPlaced() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            NetworkNode node = new NetworkNode(worldPosition, NetworkNode.NodeType.ANTENNA);
            graph.addNode(node);
        }
    }

    public void onRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            graph.removeNode(worldPosition);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("antennaName", antennaName);
        tag.putBoolean("is3GEnabled", is3GEnabled);
        tag.putBoolean("is4GEnabled", is4GEnabled);
        tag.putBoolean("is5GEnabled", is5GEnabled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("antennaName")) {
            antennaName = tag.getString("antennaName");
        }
        is3GEnabled = tag.getBoolean("is3GEnabled");
        is4GEnabled = tag.getBoolean("is4GEnabled");
        is5GEnabled = tag.getBoolean("is5GEnabled");
    }

    @Override
    public CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public String getAntennaName() {
        return antennaName;
    }

    public void setAntennaName(String name) {
        this.antennaName = name;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public boolean is3GEnabled() {
        return is3GEnabled;
    }

    public void set3GEnabled(boolean enabled) {
        this.is3GEnabled = enabled;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public boolean is4GEnabled() {
        return is4GEnabled;
    }

    public void set4GEnabled(boolean enabled) {
        this.is4GEnabled = enabled;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public boolean is5GEnabled() {
        return is5GEnabled;
    }

    public void set5GEnabled(boolean enabled) {
        this.is5GEnabled = enabled;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
}
