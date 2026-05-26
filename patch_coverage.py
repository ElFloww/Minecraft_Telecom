import sys

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content = f.read()

import re

# Find the start and end of CoverageTileHandler
start_idx = content.find("class CoverageTileHandler implements HttpHandler {")
end_idx = content.find("private void sendEmptyResponse(HttpExchange exchange, int code)", start_idx)

if start_idx == -1 or end_idx == -1:
    print("Could not find CoverageTileHandler")
    sys.exit(1)

new_handler = """    class CoverageMapHandler implements HttpHandler {
        private final java.util.Map<String, String> cache = new java.util.concurrent.ConcurrentHashMap<>();
        private long lastCacheClear = 0;

        @Override
        public void handle(HttpExchange exchange) throws java.io.IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            
            if (minecraftServer == null || minecraftServer.overworld() == null) {
                sendEmptyResponse(exchange, 500);
                return;
            }

            net.minecraft.server.level.ServerLevel level = minecraftServer.overworld();
            com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get(level);

            java.util.Map<String, String> queryPairs = new java.util.HashMap<>();
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                String[] pairs = query.split("&");
                for (String pair : pairs) {
                    int idx = pair.indexOf("=");
                    if (idx > 0) {
                        queryPairs.put(pair.substring(0, idx), pair.substring(idx + 1));
                    }
                }
            }

            String techFilter = queryPairs.get("tech");
            if (techFilter == null) {
                sendEmptyResponse(exchange, 400);
                return;
            }
            
            // Un-escape URL encoding
            techFilter = java.net.URLDecoder.decode(techFilter, java.nio.charset.StandardCharsets.UTF_8);

            // Simple cache invalidation every 30 seconds to refresh coverage
            long now = System.currentTimeMillis();
            if (now - lastCacheClear > 30000) {
                cache.clear();
                lastCacheClear = now;
            }

            if (cache.containsKey(techFilter)) {
                String response = cache.get(techFilter);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                java.io.OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
                return;
            }

            java.util.List<com.florentdubut.telecom.network.NetworkNode> activeAntennas = new java.util.ArrayList<>();
            for (com.florentdubut.telecom.network.NetworkNode node : graph.getNodes()) {
                if (node.getType() == com.florentdubut.telecom.network.NetworkNode.NodeType.ANTENNA) {
                    int mask = node.getFrequenciesMask();
                    boolean hasTech = false;
                    for (com.florentdubut.telecom.network.TelecomFrequency freq : com.florentdubut.telecom.network.TelecomFrequency.values()) {
                        if (((mask & (1 << freq.ordinal())) != 0) && freq.getTechnology().equals(techFilter)) {
                            hasTech = true;
                            break;
                        }
                    }
                    if (hasTech) activeAntennas.add(node);
                }
            }

            int maxRangeBlocks = 0;
            for (com.florentdubut.telecom.network.TelecomFrequency freq : com.florentdubut.telecom.network.TelecomFrequency.values()) {
                if (freq.getTechnology().equals(techFilter)) {
                    int d = 1;
                    while (d <= 1500) {
                        float loss = (float) (20 * Math.log10(d) + 20 * Math.log10(freq.getFrequencyMhz()) - 27.55 + d * freq.getBaseAttenuation());
                        if (-loss <= -120) break;
                        d++;
                    }
                    if (d > maxRangeBlocks) maxRangeBlocks = d;
                }
            }
            if (maxRangeBlocks > 1000) maxRangeBlocks = 1000;
            int maxChunks = (int) Math.ceil(maxRangeBlocks / 16.0);

            // Map ChunkPos to max powerDbm
            java.util.Map<Long, Float> coverageMap = new java.util.HashMap<>();

            for (com.florentdubut.telecom.network.NetworkNode antenna : activeAntennas) {
                net.minecraft.core.BlockPos antPos = antenna.getPosition();
                int antCx = antPos.getX() >> 4;
                int antCz = antPos.getZ() >> 4;

                int mask = antenna.getFrequenciesMask();
                java.util.List<com.florentdubut.telecom.network.TelecomFrequency> activeFreqs = new java.util.ArrayList<>();
                for (com.florentdubut.telecom.network.TelecomFrequency freq : com.florentdubut.telecom.network.TelecomFrequency.values()) {
                    if (((mask & (1 << freq.ordinal())) != 0) && freq.getTechnology().equals(techFilter)) {
                        activeFreqs.add(freq);
                    }
                }

                for (int cx = antCx - maxChunks; cx <= antCx + maxChunks; cx++) {
                    for (int cz = antCz - maxChunks; cz <= antCz + maxChunks; cz++) {
                        int worldX = cx * 16 + 8;
                        int worldZ = cz * 16 + 8;
                        double distance = Math.sqrt(Math.pow(worldX - antPos.getX(), 2) + Math.pow(worldZ - antPos.getZ(), 2));
                        if (distance > maxRangeBlocks) continue;

                        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                        if (y <= level.getMinBuildHeight() + 1) y = 64; // Fallback for unloaded chunk

                        net.minecraft.core.BlockPos targetPos = new net.minecraft.core.BlockPos(worldX, y + 1, worldZ);
                        
                        float bestPower = -999f;
                        for (com.florentdubut.telecom.network.TelecomFrequency freq : activeFreqs) {
                            com.florentdubut.telecom.network.SignalPropagator.SignalResult res = com.florentdubut.telecom.network.SignalPropagator.calculateSignal(level, antPos, targetPos, freq);
                            if (res.powerDbm > bestPower) bestPower = res.powerDbm;
                        }

                        if (bestPower > -120f) {
                            long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                            coverageMap.put(key, Math.max(coverageMap.getOrDefault(key, -999f), bestPower));
                        }
                    }
                }
            }

            com.google.gson.JsonArray responseArray = new com.google.gson.JsonArray();
            for (java.util.Map.Entry<Long, Float> entry : coverageMap.entrySet()) {
                long key = entry.getKey();
                int cx = (int) (key >> 32);
                int cz = (int) key;
                float power = entry.getValue();
                
                int levelColor = 1; // weak
                if (power > -80f) levelColor = 3; // strong
                else if (power > -100f) levelColor = 2; // medium
                
                com.google.gson.JsonArray tile = new com.google.gson.JsonArray();
                tile.add(cx);
                tile.add(cz);
                tile.add(levelColor);
                responseArray.add(tile);
            }

            String jsonResponse = responseArray.toString();
            cache.put(techFilter, jsonResponse);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);
            java.io.OutputStream os = exchange.getResponseBody();
            os.write(jsonResponse.getBytes());
            os.close();
        }
    }
"""

content = content[:start_idx] + new_handler + content[end_idx:]

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content)

