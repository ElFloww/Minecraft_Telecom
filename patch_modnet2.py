import sys

with open("src/main/java/com/florentdubut/telecom/network/ModNetworking.java") as f:
    content = f.read()

# Register packet
register = """
        registrar.playToServer(
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.TYPE,
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.STREAM_CODEC,
            ModNetworking::handleToggleNperf
        );
"""
content = content.replace("public static void register(final RegisterPayloadHandlersEvent event) {", "public static void register(final RegisterPayloadHandlersEvent event) {\n" + register)

# Handle packet
handler = """
    private static void handleToggleNperf(final com.florentdubut.telecom.network.packet.ToggleNperfPayload payload, final net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.world.entity.player.Player player = context.player();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get())) {
                    stack.getOrCreateTag().putBoolean("nperfActive", payload.enabled());
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("Nperf " + (payload.enabled() ? "Activated" : "Deactivated")), true);
                    break;
                }
            }
        });
    }
"""
content = content + handler

with open("src/main/java/com/florentdubut/telecom/network/ModNetworking.java", "w") as f:
    f.write(content)

