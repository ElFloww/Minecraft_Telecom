package com.florentdubut.telecom.block.entity;

import com.florentdubut.telecom.block.MicrowaveDishBlock;
import com.florentdubut.telecom.network.MicrowaveConfig;
import com.florentdubut.telecom.network.MicrowaveLinkService;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.NetworkDiagnostics;
import com.florentdubut.telecom.network.NetworkTracer;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.florentdubut.telecom.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Objects;

public class MicrowaveDishBlockEntity extends BlockEntity {
    public static final int MAX_NAME_LENGTH = 32;
    private MicrowaveConfig config = MicrowaveConfig.DEFAULT;
    private String dishName = "FH";

    public MicrowaveDishBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MICROWAVE_DISH_BE.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!isRemoved()) restoreNode();
    }

    private void restoreNode() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(serverLevel);
        NetworkNode node = graph.getNode(worldPosition);
        if (node == null) {
            node = new NetworkNode(worldPosition, NetworkNode.NodeType.MICROWAVE_DISH);
            node.setMicrowaveConfig(config);
            graph.addNode(node);
            MicrowaveLinkService.invalidateEndpoint(serverLevel, worldPosition);
            NetworkTracer.scheduleRecalculation(serverLevel, NetworkDiagnostics.Cause.NODE_LOAD);
        } else if (!node.getMicrowaveConfig().equals(config)) {
            node.setMicrowaveConfig(config);
            graph.setDirty();
            MicrowaveLinkService.invalidateEndpoint(serverLevel, worldPosition);
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel serverLevel) {
            TelecomNetworkGraph.get(serverLevel).removeNode(worldPosition);
            MicrowaveLinkService.invalidateEndpoint(serverLevel, worldPosition);
            NetworkTracer.scheduleRecalculation(serverLevel, NetworkDiagnostics.Cause.NODE_REMOVE);
        }
    }

    public MicrowaveConfig getConfig() { return config; }

    public void setConfig(MicrowaveConfig value) {
        Objects.requireNonNull(value);
        if (config.equals(value)) return;
        config = value;
        setChanged();
        if (level instanceof ServerLevel) {
            restoreNode();
            BlockState oldState = getBlockState();
            BlockState newState = oldState.setValue(MicrowaveDishBlock.FACING,
                    MicrowaveDishBlock.facingForAzimuth(config.azimuthDegrees()));
            if (oldState != newState) level.setBlock(worldPosition, newState, Block.UPDATE_CLIENTS);
            else level.sendBlockUpdated(worldPosition, oldState, oldState, Block.UPDATE_CLIENTS);
        }
    }

    public String getDishName() { return dishName; }

    public static boolean validName(String name) {
        return name != null && name.length() <= MAX_NAME_LENGTH && name.codePoints().noneMatch(Character::isISOControl);
    }

    public void setDishName(String name) {
        if (!validName(name)) throw new IllegalArgumentException("Invalid dish name");
        if (dishName.equals(name)) return;
        dishName = name;
        setChanged();
        if (level instanceof ServerLevel) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("dishName", dishName);
        config.writeTo(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        String name = input.getStringOr("dishName", "FH");
        dishName = validName(name) ? name : "FH";
        config = MicrowaveConfig.read(input);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
