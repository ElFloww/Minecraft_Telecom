package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.ModNetworking;
import com.florentdubut.telecom.network.packet.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkMapScreenTest {
    @Test
    void rejectsClosedReopenedOtherDimensionAndOldConnectionResponsesWithoutOpening() throws Exception {
        Minecraft minecraft = minecraft();
        var context = mock(IPayloadContext.class);
        var connection = minecraft.getConnection().getConnection();
        when(context.connection()).thenReturn(connection);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return CompletableFuture.completedFuture(null); })
                .when(context).enqueueWork(any(Runnable.class));
        try (var singleton = mockStatic(Minecraft.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            var first = screen(minecraft);
            var old = packet(first, "minecraft:overworld");
            receive(old, context);
            assertEquals(1, ((List<?>) field(first, "microwaveLinks")).size());
            first.removed();
            minecraft.screen = null;
            receive(old, context);
            assertNull(minecraft.screen);
            var second = screen(minecraft);
            receive(old, context);
            assertTrue(((List<?>) field(second, "microwaveLinks")).isEmpty());
            receive(packet(second, "minecraft:the_nether"), context);
            assertTrue(((List<?>) field(second, "microwaveLinks")).isEmpty());
            when(context.connection()).thenReturn(mock(Connection.class));
            receive(packet(second, "minecraft:overworld"), context);
            assertTrue(((List<?>) field(second, "microwaveLinks")).isEmpty());
            when(context.connection()).thenReturn(connection);
            receive(packet(second, "minecraft:overworld"), context);
            assertEquals(1, ((List<?>) field(second, "microwaveLinks")).size());
            verify(minecraft, never()).setScreen(any());
        }
    }

    @Test
    void liveRefreshIsCorrelatedAndAtMostEveryFortyTicksEvenAfterData() throws Exception {
        Minecraft minecraft = minecraft();
        try (var singleton = mockStatic(Minecraft.class); var packets = mockStatic(ClientPacketDistributor.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            var screen = screen(minecraft);
            for (int i = 0; i < 81; i++) {
                if (i == 2) screen.receiveData(packet(screen, "minecraft:overworld"));
                screen.tick();
            }
            var expected = new RequestNetworkMapPayload((UUID) field(screen, "viewId"), "minecraft:overworld");
            packets.verify(() -> ClientPacketDistributor.sendToServer(expected), times(3));
            screen.removed();
            for (int i = 0; i < 80; i++) screen.tick();
            packets.verifyNoMoreInteractions();
        }
    }

    @Test
    void failedBeamsRenderAndHoverWithBoundedClippingAndDegenerateBeamsStayInvisible() throws Exception {
        Minecraft minecraft = minecraft();
        NetworkMapScreen screen;
        try (var singleton = mockStatic(Minecraft.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            screen = screen(minecraft);
        }
        screen.width = 800;
        set(screen, "mapTop", 60);
        set(screen, "mapBottom", 400);
        var graphics = mock(GuiGraphics.class);
        when(graphics.pose()).thenReturn(new org.joml.Matrix3x2fStack(8));
        var method = NetworkMapScreen.class.getDeclaredMethod("renderMicrowaveLinks", GuiGraphics.class, int.class, int.class);
        method.setAccessible(true);
        for (String state : List.of("ready", "degraded", "blocked", "fresnel_blocked", "pending", "limit")) {
            var link = new MapMicrowaveData(new BlockPos(-29999999, 64, 0), new BlockPos(29999999, 64, 0), state, 0, 600, 1, null);
            set(screen, "microwaveLinks", List.of(link));
            assertSame(link, method.invoke(screen, graphics, 200, 230));
            verify(graphics, atMost(68)).fill(anyInt(), anyInt(), anyInt(), anyInt(), eq(MicrowaveMapGeometry.color(state)));
            assertEquals(new org.joml.Matrix3x2fStack(8), graphics.pose());
            clearInvocations(graphics);
        }
        set(screen, "microwaveLinks", List.of(new MapMicrowaveData(BlockPos.ZERO, BlockPos.ZERO, "unpaired", 0, 600, 0, null)));
        assertNull(method.invoke(screen, graphics, 400, 230));
        verifyNoInteractions(graphics);
        assertNull(MicrowaveMapGeometry.clip(-10, -10, -20, -20, 800, 60, 400));
        assertFalse(MicrowaveMapGeometry.hit(null, 0, 0));
    }

    private static Minecraft minecraft() {
        var minecraft = mock(Minecraft.class);
        minecraft.level = mock(ClientLevel.class);
        minecraft.player = mock(LocalPlayer.class);
        when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
        var listener = mock(ClientPacketListener.class);
        when(listener.getConnection()).thenReturn(mock(Connection.class));
        when(minecraft.getConnection()).thenReturn(listener);
        return minecraft;
    }

    private static NetworkMapScreen screen(Minecraft minecraft) throws Exception {
        var screen = new NetworkMapScreen();
        var field = Screen.class.getDeclaredField("minecraft");
        field.setAccessible(true);
        field.set(screen, minecraft);
        set(screen, "dimension", "minecraft:overworld");
        minecraft.screen = screen;
        return screen;
    }

    private static NetworkMapResponsePayload packet(NetworkMapScreen screen, String dimension) throws Exception {
        return new NetworkMapResponsePayload((UUID) field(screen, "viewId"), dimension, List.of(),
                List.of(new MapMicrowaveData(BlockPos.ZERO, BlockPos.ZERO.east(), "blocked", 0, 600, 0.5, BlockPos.ZERO.above())));
    }

    private static Object field(NetworkMapScreen screen, String name) throws Exception {
        var field = NetworkMapScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(screen);
    }

    private static void set(NetworkMapScreen screen, String name, Object value) throws Exception {
        var field = NetworkMapScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(screen, value);
    }

    private static void receive(NetworkMapResponsePayload payload, IPayloadContext context) throws Exception {
        var handler = ModNetworking.class.getDeclaredMethod("handleNetworkMapResponse", NetworkMapResponsePayload.class, IPayloadContext.class);
        handler.setAccessible(true);
        handler.invoke(null, payload, context);
    }
}
