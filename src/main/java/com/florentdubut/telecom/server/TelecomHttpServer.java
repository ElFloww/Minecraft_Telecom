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
            
            // Handle CORS for local dev
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                    exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                String response = "Telecom API is running";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
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
                
                if (node.getType() == NetworkNode.NodeType.ANTENNA) {
                    BlockEntity be = level.getBlockEntity(node.getPosition());
                    if (be instanceof AntennaBlockEntity antenna) {
                        JsonArray techs = new JsonArray();
                        for (TelecomFrequency freq : TelecomFrequency.values()) {
                            if (antenna.isFrequencyEnabled(freq)) {
                                techs.add(freq.getTechnology());
                            }
                        }
                        nodeObj.add("technologies", techs);
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
                edgeObj.addProperty("usage", edge.getCurrentUsage());
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
