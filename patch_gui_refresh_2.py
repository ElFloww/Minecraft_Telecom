import os

mod_networking_path = "src/main/java/com/florentdubut/telecom/network/ModNetworking.java"
router_screen_path = "src/main/java/com/florentdubut/telecom/client/gui/RouterScreen.java"
server_screen_path = "src/main/java/com/florentdubut/telecom/client/gui/ServerScreen.java"

with open(mod_networking_path) as f:
    networking = f.read()

# Add registration
old_reg = """    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(TelecomMod.MODID);"""

new_reg = """    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(TelecomMod.MODID);
        
        registrar.playToServer(
            com.florentdubut.telecom.network.packet.GuiRefreshRequestPayload.TYPE,
            com.florentdubut.telecom.network.packet.GuiRefreshRequestPayload.STREAM_CODEC,
            ModNetworking::handleGuiRefreshRequest
        );"""

networking = networking.replace(old_reg, new_reg)

# Add handler
old_handler = """    private static void handleNetworkToolRefreshRequest(com.florentdubut.telecom.network.packet.NetworkToolRefreshRequestPayload payload, IPayloadContext context) {"""

new_handler = """    private static void handleGuiRefreshRequest(com.florentdubut.telecom.network.packet.GuiRefreshRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) context.player();
            if (player != null) {
                net.minecraft.world.level.block.state.BlockState state = player.serverLevel().getBlockState(payload.pos());
                net.minecraft.world.phys.BlockHitResult hitResult = new net.minecraft.world.phys.BlockHitResult(
                    net.minecraft.world.phys.Vec3.atCenterOf(payload.pos()), 
                    net.minecraft.core.Direction.UP, 
                    payload.pos(), 
                    false
                );
                // Trigger the block's useWithoutItem which sends the GUI sync packet back
                if (state.getBlock() instanceof com.florentdubut.telecom.block.TelecomBlock) {
                    state.getBlock().useWithoutItem(state, player.serverLevel(), payload.pos(), player, hitResult);
                }
            }
        });
    }

    private static void handleNetworkToolRefreshRequest(com.florentdubut.telecom.network.packet.NetworkToolRefreshRequestPayload payload, IPayloadContext context) {"""

networking = networking.replace(old_handler, new_handler)

with open(mod_networking_path, "w") as f:
    f.write(networking)


with open(router_screen_path) as f:
    router = f.read()

router = router.replace("NetworkToolRefreshRequestPayload", "GuiRefreshRequestPayload")

with open(router_screen_path, "w") as f:
    f.write(router)


with open(server_screen_path) as f:
    server = f.read()

server = server.replace("NetworkToolRefreshRequestPayload", "GuiRefreshRequestPayload")

with open(server_screen_path, "w") as f:
    f.write(server)

