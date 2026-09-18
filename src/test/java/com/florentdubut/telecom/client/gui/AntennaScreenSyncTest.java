package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.AntennaRadioConfig;
import com.florentdubut.telecom.network.ModNetworking;
import com.florentdubut.telecom.network.packet.AntennaGuiSyncPayload;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AntennaScreenSyncTest {
    private Minecraft minecraft;
    private Connection connection;
    private IPayloadContext context;
    private MockedStatic<Minecraft> singleton;
    private final List<Runnable> queued = new ArrayList<>();

    @BeforeEach
    void setUp() {
        minecraft = mock(Minecraft.class);
        minecraft.level = mock(ClientLevel.class);
        when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
        var listener = mock(ClientPacketListener.class);
        connection = mock(Connection.class);
        when(listener.getConnection()).thenReturn(connection);
        when(minecraft.getConnection()).thenReturn(listener);
        doAnswer(call -> { minecraft.screen = call.getArgument(0); return null; }).when(minecraft).setScreen(any());
        singleton = mockStatic(Minecraft.class);
        singleton.when(Minecraft::getInstance).thenReturn(minecraft);
        context = mock(IPayloadContext.class);
        when(context.connection()).thenReturn(connection);
        doAnswer(call -> {
            queued.add(call.getArgument(0));
            return CompletableFuture.completedFuture(null);
        }).when(context).enqueueWork(any(Runnable.class));
    }

    @AfterEach
    void tearDown() { singleton.close(); }

    @Test
    void delayedRefreshCannotReopenClosedAntennaOrSuppressOpeningAnother() throws Exception {
        var a = packet(1, BlockPos.ZERO, false, "minecraft:overworld");
        receive(a);
        queued.removeFirst().run();
        assertNull(minecraft.screen);
        var b = packet(2, BlockPos.ZERO.east(), true, "minecraft:overworld");
        receive(b);
        queued.removeFirst().run();
        assertEquals(b, displayed());
        receive(a);
        queued.removeFirst().run();
        assertEquals(b, displayed());
    }

    @Test
    void reopeningSamePositionRejectsOldViewAndAcceptsCurrentTelemetry() throws Exception {
        var opening = packet(2, BlockPos.ZERO, true, "minecraft:overworld");
        minecraft.screen = new AntennaScreen(opening);
        receive(packet(1, BlockPos.ZERO, false, "minecraft:overworld"));
        queued.removeFirst().run();
        assertSame(opening, displayed());
        var refresh = packet(2, BlockPos.ZERO, false, "minecraft:overworld");
        receive(refresh);
        queued.removeFirst().run();
        assertSame(refresh, displayed());
    }

    @Test
    void wrongDimensionAndOldConnectionCannotOpenAView() throws Exception {
        receive(packet(1, BlockPos.ZERO, true, "minecraft:the_nether"));
        queued.removeFirst().run();
        when(context.connection()).thenReturn(mock(Connection.class));
        receive(packet(1, BlockPos.ZERO, true, "minecraft:overworld"));
        queued.removeFirst().run();
        assertNull(minecraft.screen);
        verify(minecraft, never()).setScreen(any());
    }

    private AntennaGuiSyncPayload displayed() throws Exception {
        var field = AntennaScreen.class.getDeclaredField("payload");
        field.setAccessible(true);
        return (AntennaGuiSyncPayload) field.get(minecraft.screen);
    }

    private static AntennaGuiSyncPayload packet(int view, BlockPos pos, boolean open, String dimension) {
        return new AntennaGuiSyncPayload(pos, "Site", 1, Map.of(), AntennaRadioConfig.DEFAULT, dimension, new UUID(0, view), open);
    }

    private void receive(AntennaGuiSyncPayload payload) throws Exception {
        var handler = ModNetworking.class.getDeclaredMethod("handleAntennaGuiSync", AntennaGuiSyncPayload.class, IPayloadContext.class);
        handler.setAccessible(true);
        handler.invoke(null, payload, context);
    }
}
