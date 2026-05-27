import os

mod_networking_path = "src/main/java/com/florentdubut/telecom/network/ModNetworking.java"
router_screen_path = "src/main/java/com/florentdubut/telecom/client/gui/RouterScreen.java"
server_screen_path = "src/main/java/com/florentdubut/telecom/client/gui/ServerScreen.java"

with open(mod_networking_path) as f:
    networking = f.read()

# Add handle RouterGuiSync
old_handle_router = """    private static void handleRouterGuiSync(final com.florentdubut.telecom.network.packet.RouterGuiSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.RouterScreen(payload));
        });
    }"""
new_handle_router = """    private static void handleRouterGuiSync(final com.florentdubut.telecom.network.packet.RouterGuiSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.gui.screens.Screen current = net.minecraft.client.Minecraft.getInstance().screen;
            if (current instanceof com.florentdubut.telecom.client.gui.RouterScreen rs) {
                rs.updatePayload(payload);
            } else {
                net.minecraft.client.Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.RouterScreen(payload));
            }
        });
    }"""
networking = networking.replace(old_handle_router, new_handle_router)

old_handle_server = """    private static void handleServerGuiSync(final com.florentdubut.telecom.network.packet.ServerGuiSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.ServerScreen(payload));
        });
    }"""
new_handle_server = """    private static void handleServerGuiSync(final com.florentdubut.telecom.network.packet.ServerGuiSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.gui.screens.Screen current = net.minecraft.client.Minecraft.getInstance().screen;
            if (current instanceof com.florentdubut.telecom.client.gui.ServerScreen ss) {
                ss.updatePayload(payload);
            } else {
                net.minecraft.client.Minecraft.getInstance().setScreen(new com.florentdubut.telecom.client.gui.ServerScreen(payload));
            }
        });
    }"""
networking = networking.replace(old_handle_server, new_handle_server)

with open(mod_networking_path, "w") as f:
    f.write(networking)


with open(router_screen_path) as f:
    router = f.read()

old_router_payload = "private final RouterGuiSyncPayload payload;"
new_router_payload = """private RouterGuiSyncPayload payload;
    private int refreshTick = 0;
    
    public void updatePayload(RouterGuiSyncPayload newPayload) {
        this.payload = newPayload;
    }
    
    @Override
    public void tick() {
        super.tick();
        refreshTick++;
        if (refreshTick >= 20) {
            refreshTick = 0;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.NetworkToolRefreshRequestPayload(payload.pos()));
        }
    }"""
router = router.replace(old_router_payload, new_router_payload)

with open(router_screen_path, "w") as f:
    f.write(router)


with open(server_screen_path) as f:
    server = f.read()

old_server_payload = "private final ServerGuiSyncPayload payload;"
new_server_payload = """private ServerGuiSyncPayload payload;
    private int refreshTick = 0;
    
    public void updatePayload(ServerGuiSyncPayload newPayload) {
        this.payload = newPayload;
    }
    
    @Override
    public void tick() {
        super.tick();
        refreshTick++;
        if (refreshTick >= 20) {
            refreshTick = 0;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.NetworkToolRefreshRequestPayload(payload.pos()));
        }
    }"""
server = server.replace(old_server_payload, new_server_payload)

with open(server_screen_path, "w") as f:
    f.write(server)

