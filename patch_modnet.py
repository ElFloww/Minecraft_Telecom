import sys

with open("src/main/java/com/florentdubut/telecom/network/ModNetworking.java") as f:
    content = f.read()

# Add Nperf recording logic in scanForPlayer
nperf_logic = """
        // Find if player has Nperf active on their phone
        boolean nperfActive = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(com.florentdubut.telecom.registry.ModItems.SMARTPHONE.get())) {
                if (stack.hasTag() && stack.getTag().getBoolean("nperfActive")) {
                    nperfActive = true;
                    break;
                }
            }
        }

        if (nperfActive && activeTech != null && bestSignal > -120f) {
            int techId = 0;
            switch(activeTech) {
                case "2G": techId = 1; break;
                case "3G": techId = 2; break;
                case "4G": techId = 3; break;
                case "5G": techId = 5; break;
            }
            if (activeTech.equals("4G") && activeHits.size() > 1) {
                techId = 4; // 4G+
            }
            
            int signalLevel = 1;
            if (bestSignal > -60f) signalLevel = 4;
            else if (bestSignal > -80f) signalLevel = 3;
            else if (bestSignal > -100f) signalLevel = 2;
            
            if (techId > 0) {
                graph.addCoverageRecord(player.blockPosition(), techId, signalLevel);
            }
        }

        int frequenciesMask = 0;
"""
content = content.replace("        int frequenciesMask = 0;", nperf_logic)

with open("src/main/java/com/florentdubut/telecom/network/ModNetworking.java", "w") as f:
    f.write(content)

