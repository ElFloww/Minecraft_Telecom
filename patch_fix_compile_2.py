import os
mod_networking = "src/main/java/com/florentdubut/telecom/network/ModNetworking.java"

with open(mod_networking) as f:
    text = f.read()

text = text.replace("state.useWithoutItem(player.inventory.getSelected(), player.serverLevel(), player, hitResult);", "state.useWithoutItem(player.serverLevel(), player, hitResult);")

with open(mod_networking, "w") as f:
    f.write(text)

