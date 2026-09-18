package com.florentdubut.telecom.network;

import com.florentdubut.telecom.client.gui.MicrowaveDishScreen;
import com.florentdubut.telecom.network.packet.MicrowaveGuiSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MicrowaveGuiSyncTest {
    private Minecraft minecraft;
    private IPayloadContext context;
    private MockedStatic<Minecraft> singleton;
    private final List<Runnable> queued = new ArrayList<>();

    @BeforeEach
    void setUp() {
        minecraft = mock(Minecraft.class);
        minecraft.level = mock(ClientLevel.class);
        when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
        var listener = mock(ClientPacketListener.class);
        var connection = mock(Connection.class);
        when(listener.getConnection()).thenReturn(connection);
        when(minecraft.getConnection()).thenReturn(listener);
        doAnswer(call -> { minecraft.screen = call.getArgument(0); return null; }).when(minecraft).setScreen(any());
        singleton = mockStatic(Minecraft.class);
        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
        context = mock(IPayloadContext.class);
        when(context.connection()).thenReturn(connection);
        doAnswer(call -> { queued.add(call.getArgument(0)); return CompletableFuture.completedFuture(null); })
                .when(context).enqueueWork(any(Runnable.class));
    }

    @AfterEach
    void tearDown() { singleton.close(); }

    @Test
    void refreshCannotOpenClosedScreenOrReplaceAnotherDishView() throws Exception {
        receive(packet(1, BlockPos.ZERO, false, "minecraft:overworld"));
        assertNull(minecraft.screen);
        var other = packet(2, BlockPos.ZERO.east(), true, "minecraft:overworld");
        receive(other);
        assertSame(other, displayed());
        receive(packet(1, BlockPos.ZERO, false, "minecraft:overworld"));
        assertSame(other, displayed());
        minecraft.screen = null;
        receive(packet(2, BlockPos.ZERO.east(), false, "minecraft:overworld"));
        assertNull(minecraft.screen);
    }

    @Test
    void reopenSamePositionRejectsOldUuidButAcceptsCurrentRefresh() throws Exception {
        var opening = packet(2, BlockPos.ZERO, true, "minecraft:overworld");
        receive(opening);
        receive(packet(1, BlockPos.ZERO, false, "minecraft:overworld"));
        assertSame(opening, displayed());
        var update = packet(2, BlockPos.ZERO, false, "minecraft:overworld");
        receive(update);
        assertSame(update, displayed());
    }

    @Test
    void wrongDimensionDisconnectedOrReplacedConnectionCannotOpen() {
        receive(packet(1, BlockPos.ZERO, true, "minecraft:the_nether"));
        assertNull(minecraft.screen);
        MicrowaveNetworking.handleGuiSync(packet(1, BlockPos.ZERO, true, "minecraft:overworld"), context);
        when(minecraft.getConnection()).thenReturn(mock(ClientPacketListener.class));
        queued.removeFirst().run();
        assertNull(minecraft.screen);
        when(minecraft.getConnection()).thenReturn(null);
        receive(packet(1, BlockPos.ZERO, true, "minecraft:overworld"));
        assertNull(minecraft.screen);
        verify(minecraft, never()).setScreen(any());
    }

    private void receive(MicrowaveGuiSyncPayload packet) {
        MicrowaveNetworking.handleGuiSync(packet, context);
        queued.removeFirst().run();
    }

    private MicrowaveGuiSyncPayload displayed() throws Exception {
        var field = MicrowaveDishScreen.class.getDeclaredField("payload");
        field.setAccessible(true);
        return (MicrowaveGuiSyncPayload) field.get(minecraft.screen);
    }

    private static MicrowaveGuiSyncPayload packet(int view, BlockPos pos, boolean opening, String dimension) {
        return new MicrowaveGuiSyncPayload(pos, "Site", MicrowaveConfig.DEFAULT, dimension, new UUID(0, view), opening,
                pos, "disabled", 0, 600, 0, null);
    }
}
