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
            server.createContext("/api/network", new NetworkMapHandler());
            
            server.createContext("/api/tile", new TileMapHandler());
            
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
            
            if (minecraftServer == null) {
                sendEmptyResponse(exchange, 500);
                return;
            }

            ServerLevel level = minecraftServer.overworld();
            if (level == null) {
                sendEmptyResponse(exchange, 500);
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

            net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
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

    class NetworkMapHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
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
                        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(node.getPosition());
                        if (be instanceof com.florentdubut.telecom.block.entity.RouterBlockEntity router) {
                            capacityDown = router.getConfiguredMaxDown();
                            capacityUp = router.getConfiguredMaxUp();
                        }
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
}
