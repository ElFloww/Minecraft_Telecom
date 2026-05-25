package com.florentdubut.telecom.network.packet;

import net.minecraft.core.BlockPos;

public record MapNodeData(BlockPos pos, String type, String ipAddress, String extraInfo) {
}
