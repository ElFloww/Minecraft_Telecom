import os

mod_networking = "src/main/java/com/florentdubut/telecom/network/ModNetworking.java"
server_screen = "src/main/java/com/florentdubut/telecom/client/gui/ServerScreen.java"

with open(mod_networking) as f:
    text = f.read()

text = text.replace("state.getBlock().useWithoutItem(state, player.serverLevel(), payload.pos(), player, hitResult);", "state.useWithoutItem(player.inventory.getSelected(), player.serverLevel(), player, hitResult);")

with open(mod_networking, "w") as f:
    f.write(text)

with open(server_screen) as f:
    text2 = f.read()

old_server_payload = """private ServerGuiSyncPayload payload;
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
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.GuiRefreshRequestPayload(payload.pos()));
        }
    }"""
new_server_payload = """private ServerGuiSyncPayload payload;
    
    public void updatePayload(ServerGuiSyncPayload newPayload) {
        this.payload = newPayload;
    }"""

text2 = text2.replace(old_server_payload, new_server_payload)

with open(server_screen, "w") as f:
    f.write(text2)

