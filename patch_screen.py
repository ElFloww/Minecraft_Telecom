import sys

with open("src/main/java/com/florentdubut/telecom/client/gui/SmartphoneScreen.java") as f:
    content = f.read()

# Add button
button = """
        // Nperf App Button
        this.addRenderableWidget(Button.builder(Component.literal("Nperf"), b -> {
            boolean currentState = false;
            net.minecraft.world.entity.player.Player player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null) {
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
                    if (stack.is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get())) {
                        currentState = stack.hasTag() && stack.getTag().getBoolean("nperfActive");
                        break;
                    }
                }
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.ToggleNperfPayload(!currentState));
            this.onClose();
        }).bounds(startX + 85, startY + 110, 60, 60).build());
"""

content = content.replace("        // SMS App (WIP)\n", button + "\n        // SMS App (WIP)\n")

with open("src/main/java/com/florentdubut/telecom/client/gui/SmartphoneScreen.java", "w") as f:
    f.write(content)

