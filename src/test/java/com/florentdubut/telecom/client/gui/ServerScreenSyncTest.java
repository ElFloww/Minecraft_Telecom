package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.ModNetworking;
import com.florentdubut.telecom.network.packet.ServerGuiSyncPayload;
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

class ServerScreenSyncTest {
    private static final UUID VIEW = new UUID(1, 2);
    private static final BlockPos POS = new BlockPos(1, 64, 2);
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
    void refreshUpdatesOnlyMatchingView() throws Exception {
        var screen = screen(VIEW, POS);
        var payload = packet(VIEW, POS, false, true, "minecraft:overworld");
        receive(payload);
        queued.removeFirst().run();
        verify(screen).updatePayload(payload);
        verify(minecraft, never()).setScreen(any());
    }

    @Test
    void queuedRefreshAfterCloseNeverReopensScreen() throws Exception {
        var screen = screen(VIEW, POS);
        receive(packet(VIEW, POS, false, true, "minecraft:overworld"));
        minecraft.screen = null;
        queued.removeFirst().run();
        assertNull(minecraft.screen);
        verify(screen, never()).updatePayload(any());
        verify(minecraft, never()).setScreen(any());
    }

    @Test
    void oldViewCannotUpdateAnotherServerOrReopenedSameServer() throws Exception {
        screen(VIEW, POS);
        var payload = packet(VIEW, POS, false, true, "minecraft:overworld");
        receive(payload);
        var differentPosition = screen(VIEW, POS.east());
        queued.removeFirst().run();
        verify(differentPosition, never()).updatePayload(any());
        receive(payload);
        var reopened = screen(new UUID(3, 4), POS);
        queued.removeFirst().run();
        verify(reopened, never()).updatePayload(any());
        verify(minecraft, never()).setScreen(any());
    }

    @Test
    void oldConnectionAndWrongDimensionAreIgnored() throws Exception {
        var screen = screen(VIEW, POS);
        when(context.connection()).thenReturn(mock(Connection.class));
        receive(packet(VIEW, POS, false, true, "minecraft:overworld"));
        queued.removeFirst().run();
        when(context.connection()).thenReturn(connection);
        receive(packet(VIEW, POS, false, true, "minecraft:the_nether"));
        queued.removeFirst().run();
        verify(screen, never()).updatePayload(any());
        verify(minecraft, never()).setScreen(any());
    }

    @Test
    void leavingAndReturningToDimensionInvalidatesQueuedRefresh() throws Exception {
        var screen = screen(VIEW, POS);
        receive(packet(VIEW, POS, false, true, "minecraft:overworld"));
        minecraft.level = mock(ClientLevel.class);
        when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
        queued.removeFirst().run();
        verify(screen, never()).updatePayload(any());
        assertFalse(screen.matchesView(connection, packet(VIEW, POS, false, true, "minecraft:overworld")));
    }

    @Test
    void missingSourceClosesOnlyTheMatchingView() throws Exception {
        var screen = screen(VIEW, POS);
        receive(ServerGuiSyncPayload.unavailable(VIEW, "minecraft:overworld", POS, false));
        queued.removeFirst().run();
        assertNull(minecraft.screen);
        verify(screen, never()).updatePayload(any());
        var other = screen(new UUID(4, 5), POS);
        receive(ServerGuiSyncPayload.unavailable(VIEW, "minecraft:overworld", POS, false));
        queued.removeFirst().run();
        assertSame(other, minecraft.screen);
    }

    @Test
    void explicitOpeningCreatesNewViewButRefreshWithoutViewDoesNothing() throws Exception {
        receive(packet(VIEW, POS, false, true, "minecraft:overworld"));
        queued.removeFirst().run();
        assertNull(minecraft.screen);
        receive(packet(VIEW, POS, true, true, "minecraft:overworld"));
        queued.removeFirst().run();
        assertInstanceOf(ServerScreen.class, minecraft.screen);
        assertTrue(((ServerScreen) minecraft.screen).matchesView(connection, packet(VIEW, POS, false, true, "minecraft:overworld")));
    }

    private ServerScreen screen(UUID view, BlockPos pos) {
        var screen = spy(new ServerScreen(packet(view, pos, true, true, "minecraft:overworld")));
        minecraft.screen = screen;
        return screen;
    }

    private static ServerGuiSyncPayload packet(UUID view, BlockPos pos, boolean open, boolean valid, String dimension) {
        return new ServerGuiSyncPayload(view, dimension, open, valid, pos, 1, 2, 3, 100, 50, 1000000, 1000000);
    }

    private void receive(ServerGuiSyncPayload payload) throws Exception {
        var handler = ModNetworking.class.getDeclaredMethod("handleServerGuiSync", ServerGuiSyncPayload.class, IPayloadContext.class);
        handler.setAccessible(true);
        handler.invoke(null, payload, context);
    }
}
