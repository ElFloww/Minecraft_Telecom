import sys

with open("src/main/java/com/florentdubut/telecom/client/gui/SmartphoneHUD.java") as f:
    content = f.read()

bad_hud = """        boolean hasPhone = mc.player.getInventory().contains(new net.minecraft.world.item.ItemStack(ModItems.SMARTPHONE.get()));
        if (!hasPhone && !mc.player.getOffhandItem().is(ModItems.SMARTPHONE.get()) && !mc.player.getMainHandItem().is(ModItems.SMARTPHONE.get())) {
            return;
        }"""
good_hud = """        boolean hasPhone = false;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (mc.player.getInventory().getItem(i).is(ModItems.SMARTPHONE.get())) {
                hasPhone = true;
                break;
            }
        }
        if (!hasPhone) return;"""

content = content.replace(bad_hud, good_hud)

with open("src/main/java/com/florentdubut/telecom/client/gui/SmartphoneHUD.java", "w") as f:
    f.write(content)

with open("src/main/java/com/florentdubut/telecom/event/ServerEvents.java") as f:
    content2 = f.read()

bad_server = """                boolean hasPhone = player.getInventory().contains(new net.minecraft.world.item.ItemStack(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get()));
                if (hasPhone || player.getOffhandItem().is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get()) || player.getMainHandItem().is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get())) {"""

good_server = """                boolean hasPhone = false;
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    if (player.getInventory().getItem(i).is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get())) {
                        hasPhone = true;
                        break;
                    }
                }
                if (hasPhone) {"""
content2 = content2.replace(bad_server, good_server)

with open("src/main/java/com/florentdubut/telecom/event/ServerEvents.java", "w") as f:
    f.write(content2)

