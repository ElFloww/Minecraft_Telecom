package com.florentdubut.telecom.network;

import com.florentdubut.telecom.TelecomMod;
import com.florentdubut.telecom.block.entity.MicrowaveDishBlockEntity;
import com.florentdubut.telecom.network.packet.MicrowaveConfigPayload;
import com.florentdubut.telecom.network.packet.MicrowaveGuiSyncPayload;
import com.florentdubut.telecom.network.packet.MicrowaveRefreshRequestPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

@EventBusSubscriber(modid = TelecomMod.MODID)
public final class MicrowaveNetworking {
    public static final int MAX_PEER_DISTANCE = MicrowaveLinkEvaluator.MAX_RANGE;
    // Server-thread only, fixed storage per player; no value retains its weak key.
    private static final Map<ServerPlayer, long[]> COOLDOWNS = new WeakHashMap<>();
    private static final Map<ServerPlayer, View> VIEWS = new WeakHashMap<>();
    private record View(String dimension, BlockPos pos, UUID id, WeakReference<MicrowaveDishBlockEntity> dish) {}

    private MicrowaveNetworking() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1.6");
        registrar.playToServer(MicrowaveConfigPayload.TYPE, MicrowaveConfigPayload.STREAM_CODEC, MicrowaveNetworking::handleConfig);
        registrar.playToClient(MicrowaveGuiSyncPayload.TYPE, MicrowaveGuiSyncPayload.STREAM_CODEC, MicrowaveNetworking::handleGuiSync);
        registrar.playToServer(MicrowaveRefreshRequestPayload.TYPE, MicrowaveRefreshRequestPayload.STREAM_CODEC, MicrowaveNetworking::handleRefresh);
    }

    private static boolean accept(ServerPlayer player, int category, int millis) {
        if (player.hasDisconnected()) return false;
        long[] deadlines = COOLDOWNS.computeIfAbsent(player, ignored -> new long[3]);
        long now = System.nanoTime();
        if (deadlines[category] != 0 && now - deadlines[category] < 0) return false;
        deadlines[category] = now + TimeUnit.MILLISECONDS.toNanos(millis);
        return true;
    }

    private static MicrowaveDishBlockEntity accessibleDish(ServerPlayer player, BlockPos pos, String dimension) {
        ServerLevel level = player.level();
        if (player.isSpectator() || !level.dimension().identifier().toString().equals(dimension)
                || !MicrowaveConfigPayload.validPosition(pos) || !level.isInWorldBounds(pos)
                || !player.isWithinBlockInteractionRange(pos, 0) || !level.mayInteract(player, pos)
                || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return null;
        return level.getBlockEntity(pos) instanceof MicrowaveDishBlockEntity dish ? dish : null;
    }

    public static boolean validPeer(BlockPos source, BlockPos peer) {
        return peer == null || (MicrowaveConfigPayload.validPosition(peer) && !peer.equals(source)
                && source.distSqr(peer) <= (double) MAX_PEER_DISTANCE * MAX_PEER_DISTANCE);
    }

    private static MicrowaveDishBlockEntity viewedDish(ServerPlayer player, BlockPos pos, String dimension, UUID id) {
        View view = VIEWS.get(player);
        if (view == null || !view.pos().equals(pos) || !view.dimension().equals(dimension) || !view.id().equals(id)) return null;
        MicrowaveDishBlockEntity dish = view.dish().get();
        return dish != null && !dish.isRemoved() && accessibleDish(player, pos, dimension) == dish ? dish : null;
    }

    public static void openDishGuiForPlayer(ServerPlayer player, MicrowaveDishBlockEntity dish) {
        if (!accept(player, 0, 100)) return;
        String dimension = player.level().dimension().identifier().toString();
        if (dish.getLevel() != player.level() || accessibleDish(player, dish.getBlockPos(), dimension) != dish) return;
        UUID id = UUID.randomUUID();
        VIEWS.put(player, new View(dimension, dish.getBlockPos().immutable(), id, new WeakReference<>(dish)));
        sendGui(player, dish, id, true);
    }

    private static void sendGui(ServerPlayer player, MicrowaveDishBlockEntity dish, UUID view, boolean opening) {
        var status = MicrowaveLinkService.status(player.level(), dish.getBlockPos());
        PacketDistributor.sendToPlayer(player, new MicrowaveGuiSyncPayload(dish.getBlockPos(), dish.getDishName(), dish.getConfig(),
                player.level().dimension().identifier().toString(), view, opening, status.target(),
                status.state(), status.capacityMbps(), status.nominalCapacityMbps(),
                status.latencyMs(), status.blocker()));
    }

    static void handleConfig(MicrowaveConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !accept(player, 1, 100)
                    || !MicrowaveDishBlockEntity.validName(payload.name()) || !MicrowaveConfigPayload.validConfig(payload.config())
                    || !validPeer(payload.pos(), payload.config().peer())) return;
            MicrowaveDishBlockEntity dish = viewedDish(player, payload.pos(), payload.dimension(), payload.viewId());
            if (dish == null || (payload.config().peer() != null && !player.level().isInWorldBounds(payload.config().peer()))) return;
            // A peer may be unloaded: only coordinates are stored. Never read or mutate the remote block.
            dish.setDishName(payload.name());
            dish.setConfig(payload.config());
        });
    }

    static void handleRefresh(MicrowaveRefreshRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !accept(player, 2, 250)) return;
            MicrowaveDishBlockEntity dish = viewedDish(player, payload.pos(), payload.dimension(), payload.viewId());
            if (dish != null) sendGui(player, dish, payload.viewId(), false);
        });
    }

    static void handleGuiSync(MicrowaveGuiSyncPayload payload, IPayloadContext context) {
        var connection = context.connection();
        context.enqueueWork(() -> {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.getConnection() == null || minecraft.getConnection().getConnection() != connection
                    || minecraft.level == null || !minecraft.level.dimension().identifier().toString().equals(payload.dimension())) return;
            if (payload.opening()) minecraft.setScreen(new com.florentdubut.telecom.client.gui.MicrowaveDishScreen(payload));
            else if (minecraft.screen instanceof com.florentdubut.telecom.client.gui.MicrowaveDishScreen screen) screen.receiveUpdate(payload);
        });
    }
}
