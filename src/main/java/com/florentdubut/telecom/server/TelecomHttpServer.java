package com.florentdubut.telecom.server;

import com.florentdubut.telecom.network.NetworkEdge;
import com.florentdubut.telecom.network.NetworkNode;
import com.florentdubut.telecom.network.TelecomNetworkGraph;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.network.TelecomFrequency;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;

public class TelecomHttpServer {
    private HttpServer server;
    private MinecraftServer minecraftServer;

    public void start(MinecraftServer ms) {
        this.minecraftServer = ms;
        try {
            server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));
            server.createContext("/api/network", new NetworkMapHandler());
            
            server.createContext("/api/tile", new TileMapHandler());
            server.createContext("/api/nperf_map", new NperfMapHandler());
            server.createContext("/api/speedtest", new SpeedtestHandler());
            
            server.createContext("/api/player", exchange -> {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
                if (minecraftServer == null || minecraftServer.overworld() == null || minecraftServer.overworld().players().isEmpty()) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                net.minecraft.world.entity.player.Player p = minecraftServer.overworld().players().get(0);
                String json = "{\"x\":" + p.getBlockX() + ",\"z\":" + p.getBlockZ() + "}";
                byte[] bytes = json.getBytes("UTF-8");
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                java.io.OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            });
            
            server.createContext("/favicon.ico", exchange -> {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            
            // Handle CORS for local dev
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/")) {
                    path = "/index.html";
                }
                
                // Read from classpath /web
                java.io.InputStream is = TelecomHttpServer.class.getResourceAsStream("/web" + path);
                if (is == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                
                String contentType = "text/plain";
                if (path.endsWith(".html")) contentType = "text/html";
                else if (path.endsWith(".js")) contentType = "application/javascript";
                else if (path.endsWith(".css")) contentType = "text/css";
                else if (path.endsWith(".png")) contentType = "image/png";
                else if (path.endsWith(".svg")) contentType = "image/svg+xml";
                else if (path.endsWith(".ico")) contentType = "image/x-icon";
                
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(200, 0); // chunked transfer
                OutputStream os = exchange.getResponseBody();
                
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.close();
                is.close();
            });

            server.setExecutor(null);
            server.start();
            System.out.println("Telecom HTTP Server started on port 8080");
        } catch (IOException e) {
            System.err.println("Failed to start Telecom HTTP Server");
            e.printStackTrace();
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            System.out.println("Telecom HTTP Server stopped");
        }
    }
    
    class TileMapHandler implements HttpHandler {
        private final java.util.Map<Long, byte[]> tileCache = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            
            if (minecraftServer == null) {
                exchange.sendResponseHeaders(500, -1); exchange.close();
                return;
            }

            ServerLevel level = minecraftServer.overworld();
            if (level == null) {
                exchange.sendResponseHeaders(500, -1); exchange.close();
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            if (query == null) {
                sendEmptyResponse(exchange, 400);
                return;
            }

            int cx = 0;
            int cz = 0;
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) {
                    if (pair[0].equals("cx")) cx = Integer.parseInt(pair[1]);
                    if (pair[0].equals("cz")) cz = Integer.parseInt(pair[1]);
                }
            }

            long chunkKey = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
            if (tileCache.containsKey(chunkKey)) {
                sendImage(exchange, tileCache.get(chunkKey));
                return;
            }

            net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            if (chunk == null) {
                // Chunk not generated or loaded, return 404
                sendEmptyResponse(exchange, 404);
                return;
            }

            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int globalX = cx * 16 + x;
                    int globalZ = cz * 16 + z;
                    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, globalX, globalZ);
                    BlockPos pos = new BlockPos(globalX, y - 1, globalZ);
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                    
                    int color = 0xFF000000 | state.getMapColor(level, pos).col;
                    
                    // Simple shading based on height parity
                    if (y % 2 == 0) {
                        color = darken(color, 0.9f);
                    }
                    
                    image.setRGB(x, z, color); // Note: x is x, z is y in image coordinates
                }
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", baos);
            byte[] bytes = baos.toByteArray();
            
            tileCache.put(chunkKey, bytes);
            sendImage(exchange, bytes);
        }
        
        private int darken(int color, float factor) {
            int a = (color >> 24) & 0xFF;
            int r = (int)(((color >> 16) & 0xFF) * factor);
            int g = (int)(((color >> 8) & 0xFF) * factor);
            int b = (int)((color & 0xFF) * factor);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private void sendEmptyResponse(HttpExchange exchange, int code) throws IOException {
            exchange.sendResponseHeaders(code, -1);
        }

        private void sendImage(HttpExchange exchange, byte[] bytes) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }

        class SpeedtestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendEmptyResponse(exchange, 405);
                return;
            }

            if (minecraftServer == null || minecraftServer.overworld() == null) {
                exchange.sendResponseHeaders(500, -1); exchange.close();
                return;
            }
            
            try {
                java.io.InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), "UTF-8");
                JsonObject payload = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                
                long posLong = payload.get("pos").getAsLong();
                int durationTicks = payload.get("duration").getAsInt();
                BlockPos pos = BlockPos.of(posLong);
                
                ServerLevel level = minecraftServer.overworld();
                TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
                
                NetworkNode node = graph.getNode(pos);
                if (node == null || node.getType() != NetworkNode.NodeType.ROUTER) {
                    System.out.println("Node is null or not router: " + pos + " node=" + node);
                    sendEmptyResponse(exchange, 404);
                    return;
                }
                
                int maxDown = payload.has("maxDown") ? payload.get("maxDown").getAsInt() : node.getCapacityDown();
                int maxUp = payload.has("maxUp") ? payload.get("maxUp").getAsInt() : node.getCapacityUp();
                String ip = node.getIpAddress() != null ? node.getIpAddress() : "0.0.0.0";
                
                // For web-initiated tests, player is null
                graph.startSpeedtest(pos, ip, maxDown, maxUp, 0, 0, durationTicks, false, null);
                
                JsonObject response = new JsonObject();
                response.addProperty("status", "started");
                
                byte[] bytes = response.toString().getBytes("UTF-8");
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            } catch (Exception e) {
                e.printStackTrace();
                sendEmptyResponse(exchange, 400);
            }
        }
        private void sendEmptyResponse(HttpExchange exchange, int code) throws IOException {
            exchange.sendResponseHeaders(code, -1);
        }
    }

        class CoverageMapHandler implements HttpHandler {
        private final java.util.Map<String, String> cache = new java.util.concurrent.ConcurrentHashMap<>();
        private long lastCacheClear = 0;

        @Override
        public void handle(HttpExchange exchange) throws java.io.IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            
            if (minecraftServer == null || minecraftServer.overworld() == null) {
                exchange.sendResponseHeaders(500, -1); exchange.close();
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
        
        private void sendEmptyResponse(HttpExchange exchange, int code) throws IOException {
            exchange.sendResponseHeaders(code, -1);
        }
    }

    class NetworkMapHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            exchange.getResponseHeaders().add("Content-Type", "application/json");

            if (minecraftServer == null) {
                sendResponse(exchange, 500, "{\"error\": \"Server not ready\"}");
                return;
            }

            ServerLevel level = minecraftServer.overworld();
            if (level == null) {
                sendResponse(exchange, 500, "{\"error\": \"Level not ready\"}");
                return;
            }

            TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
            if (graph == null) {
                sendResponse(exchange, 500, "{\"error\": \"Graph not found\"}");
                return;
            }

            JsonObject response = new JsonObject();
            
            // Nodes
            JsonArray nodesArray = new JsonArray();
            for (NetworkNode node : graph.getNodes()) {
                JsonObject nodeObj = new JsonObject();
                nodeObj.addProperty("id", node.getPosition().asLong());
                nodeObj.addProperty("x", node.getPosition().getX());
                nodeObj.addProperty("y", node.getPosition().getY());
                nodeObj.addProperty("z", node.getPosition().getZ());
                nodeObj.addProperty("type", node.getType().name());
                nodeObj.addProperty("ip", node.getIpAddress() != null ? node.getIpAddress() : "");
                nodeObj.addProperty("cidr", node.getNetworkCidr() != null ? node.getNetworkCidr() : "");
                nodeObj.addProperty("usageDown", node.getCurrentUsageDown());
                nodeObj.addProperty("usageUp", node.getCurrentUsageUp());
                
                // Add capacity based on node type
                long capacityDown = 1000;
                long capacityUp = 1000;
                switch (node.getType()) {
                    case SERVER, NRO -> { capacityDown = 1000000; capacityUp = 1000000; }
                    case NRA, PM -> { capacityDown = 100000; capacityUp = 100000; }
                    case SR -> { capacityDown = 10000; capacityUp = 10000; }
                    case ROUTER -> {
                        capacityDown = node.getCapacityDown();
                        capacityUp = node.getCapacityUp();
                    }
                    case ANTENNA -> { capacityDown = 1000; capacityUp = 1000; }
                }
                nodeObj.addProperty("capacityDown", capacityDown);
                nodeObj.addProperty("capacityUp", capacityUp);
                nodeObj.addProperty("capacity", Math.max(capacityDown, capacityUp)); // Fallback
                
                if (node.getType() == NetworkNode.NodeType.ANTENNA) {
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(node.getPosition());
                    if (be instanceof com.florentdubut.telecom.block.entity.AntennaBlockEntity antenna) {
                        JsonArray techs = new JsonArray();
                        JsonArray freqsArray = new JsonArray();
                        java.util.Map<TelecomFrequency, TelecomNetworkGraph.AntennaFreqStats> utilization = graph.getAntennaUtilization(node.getPosition());
                        
                        for (TelecomFrequency freq : TelecomFrequency.values()) {
                            if (antenna.isFrequencyEnabled(freq)) {
                                techs.add(freq.getTechnology());
                                
                                JsonObject fObj = new JsonObject();
                                fObj.addProperty("technology", freq.getTechnology());
                                fObj.addProperty("label", freq.getFrequencyLabel());
                                fObj.addProperty("max", freq.getMaxSpeedMb());
                                
                                TelecomNetworkGraph.AntennaFreqStats stats = utilization.get(freq);
                                int actual = stats != null ? stats.actualMbps() : 0;
                                fObj.addProperty("usage", actual);
                                freqsArray.add(fObj);
                            }
                        }
                        nodeObj.add("technologies", techs);
                        nodeObj.add("frequencies", freqsArray);
                    }
                }
                nodesArray.add(nodeObj);
            }
            response.add("nodes", nodesArray);

            // Edges
            JsonArray edgesArray = new JsonArray();
            for (NetworkEdge edge : graph.getEdges()) {
                JsonObject edgeObj = new JsonObject();
                edgeObj.addProperty("source", edge.getNodeA().asLong());
                edgeObj.addProperty("target", edge.getNodeB().asLong());
                edgeObj.addProperty("type", edge.getType().name());
                edgeObj.addProperty("usageDown", edge.getCurrentUsageDown());
                edgeObj.addProperty("usageUp", edge.getCurrentUsageUp());
                edgeObj.addProperty("capacity", edge.getBandwidthMax());
                edgeObj.addProperty("length", edge.getLength());
                edgesArray.add(edgeObj);
            }
            response.add("edges", edgesArray);

            String jsonResponse = response.toString();
            sendResponse(exchange, 200, jsonResponse);
        }

        private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
            byte[] bytes = response.getBytes("UTF-8");
            exchange.sendResponseHeaders(code, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }

    class NperfMapHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws java.io.IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            
            if (minecraftServer == null || minecraftServer.overworld() == null) {
                exchange.sendResponseHeaders(500, -1); exchange.close();
                return;
            }

            net.minecraft.server.level.ServerLevel level = minecraftServer.overworld();
            com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get(level);

            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (java.util.Map.Entry<Long, Integer> entry : graph.getRecordedCoverage().entrySet()) {
                if (!first) json.append(",");
                long pos = entry.getKey();
                net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.of(pos);
                int x = bp.getX();
                int z = bp.getZ();
                int val = entry.getValue();
                int techId = val >> 8;
                int signal = val & 0xFF;
                
                json.append("{\"x\":").append(x)
                    .append(",\"z\":").append(z)
                    .append(",\"t\":").append(techId)
                    .append(",\"s\":").append(signal).append("}");
                first = false;
            }
            json.append("]");

            byte[] responseBytes = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            java.io.OutputStream os = exchange.getResponseBody();
            os.write(responseBytes);
            os.close();
        }
    }
}
