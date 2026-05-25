package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.registry.ModBlockEntities;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.network.TelecomFrequency;
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
    // Bitmask of enabled frequencies. e.g., if bit 0 is 1, then TelecomFrequency.values()[0] is enabled.
    private int enabledFrequenciesMask = 0;

    public AntennaBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ANTENNA_BE.get(), pos, state);
    }

    public void onPlaced() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            NetworkNode node = new NetworkNode(worldPosition, NetworkNode.NodeType.ANTENNA);
            graph.addNode(node);
            com.florentdubut.telecom.network.NetworkTracer.recalculateNetwork(serverLevel);
        }
    }

    public void onRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
            graph.removeNode(worldPosition);
            com.florentdubut.telecom.network.NetworkTracer.recalculateNetwork(serverLevel);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("antennaName", antennaName);
        tag.putInt("enabledFrequenciesMask", enabledFrequenciesMask);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("antennaName")) {
            antennaName = tag.getString("antennaName");
        }
        enabledFrequenciesMask = tag.getInt("enabledFrequenciesMask");
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

    public int getEnabledFrequenciesMask() {
        return enabledFrequenciesMask;
    }

    public boolean isFrequencyEnabled(TelecomFrequency freq) {
        return (enabledFrequenciesMask & (1 << freq.ordinal())) != 0;
    }

    public void setEnabledFrequenciesMask(int mask) {
        this.enabledFrequenciesMask = mask;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
}
