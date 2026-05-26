import sys
with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content = f.read()

# For NetworkMapHandler
target_capacity = """                    case ROUTER -> {
                        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(node.getPosition());
                        if (be instanceof com.florentdubut.telecom.block.entity.RouterBlockEntity router) {
                            capacityDown = router.getConfiguredMaxDown();
                            capacityUp = router.getConfiguredMaxUp();
                        }
                    }"""
replace_capacity = """                    case ROUTER -> {
                        capacityDown = node.getCapacityDown();
                        capacityUp = node.getCapacityUp();
                    }"""

target_antenna = """                    case ANTENNA -> {
                        capacityDown = 1000; capacityUp = 1000;
                        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(node.getPosition());
                        if (be instanceof com.florentdubut.telecom.block.entity.AntennaBlockEntity antenna) {
                            int mask = antenna.getEnabledFrequenciesMask();"""
replace_antenna = """                    case ANTENNA -> {
                        capacityDown = 1000; capacityUp = 1000;
                        int mask = node.getFrequenciesMask();
                        {"""

# For SpeedtestHandler
target_speedtest = """                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof com.florentdubut.telecom.block.entity.RouterBlockEntity)) {
                    sendEmptyResponse(exchange, 404);
                    return;
                }
                
                com.florentdubut.telecom.block.entity.RouterBlockEntity router = (com.florentdubut.telecom.block.entity.RouterBlockEntity) be;
                int maxDown = router.getConfiguredMaxDown();
                int maxUp = router.getConfiguredMaxUp();"""
replace_speedtest = """                int maxDown = payload.has("maxDown") ? payload.get("maxDown").getAsInt() : node.getCapacityDown();
                int maxUp = payload.has("maxUp") ? payload.get("maxUp").getAsInt() : node.getCapacityUp();"""

# For CoverageTileHandler
target_coverage = """            for (NetworkNode node : graph.getNodes()) {
                if (node.getType() == NetworkNode.NodeType.ANTENNA) {
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(node.getPosition());
                    if (be instanceof com.florentdubut.telecom.block.entity.AntennaBlockEntity antenna) {
                        boolean hasTech = false;
                        for (TelecomFrequency freq : TelecomFrequency.values()) {
                            if (antenna.isFrequencyEnabled(freq) && freq.getTechnology().equals(techFilter)) {
                                hasTech = true;
                                break;
                            }
                        }
                        if (hasTech) activeAntennas.add(antenna);
                    }
                }
            }"""
replace_coverage = """            // We store node positions to avoid BlockEntity lookups
            java.util.List<NetworkNode> activeAntennas = new java.util.ArrayList<>();
            for (NetworkNode node : graph.getNodes()) {
                if (node.getType() == NetworkNode.NodeType.ANTENNA) {
                    int mask = node.getFrequenciesMask();
                    boolean hasTech = false;
                    for (TelecomFrequency freq : TelecomFrequency.values()) {
                        if (((mask & (1 << freq.ordinal())) != 0) && freq.getTechnology().equals(techFilter)) {
                            hasTech = true;
                            break;
                        }
                    }
                    if (hasTech) activeAntennas.add(node);
                }
            }"""

target_coverage_loop = """                    for (com.florentdubut.telecom.block.entity.AntennaBlockEntity antenna : activeAntennas) {
                        BlockPos antPos = antenna.getBlockPos();
                        double distance = Math.sqrt(pos.distSqr(antPos));
                        
                        // Range optimization to avoid calculating for very far antennas
                        if (distance > 500) continue; 
                        
                        for (TelecomFrequency freq : TelecomFrequency.values()) {
                            if (antenna.isFrequencyEnabled(freq) && freq.getTechnology().equals(techFilter)) {"""
replace_coverage_loop = """                    for (NetworkNode antenna : activeAntennas) {
                        BlockPos antPos = antenna.getPosition();
                        double distance = Math.sqrt(pos.distSqr(antPos));
                        
                        // Range optimization to avoid calculating for very far antennas
                        if (distance > 500) continue; 
                        
                        int mask = antenna.getFrequenciesMask();
                        for (TelecomFrequency freq : TelecomFrequency.values()) {
                            if (((mask & (1 << freq.ordinal())) != 0) && freq.getTechnology().equals(techFilter)) {"""

content = content.replace(target_capacity, replace_capacity)
content = content.replace(target_antenna, replace_antenna)
content = content.replace(target_speedtest, replace_speedtest)
content = content.replace(target_coverage, replace_coverage)
content = content.replace(target_coverage_loop, replace_coverage_loop)

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content)
