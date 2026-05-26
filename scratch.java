    class SpeedtestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendEmptyResponse(exchange, 405);
                return;
            }

            if (minecraftServer == null || minecraftServer.overworld() == null) {
                sendEmptyResponse(exchange, 500);
                return;
            }
            
            try {
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), "UTF-8");
                JsonObject payload = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                
                long posLong = payload.get("pos").getAsLong();
                int durationTicks = payload.get("duration").getAsInt();
                BlockPos pos = BlockPos.of(posLong);
                
                ServerLevel level = minecraftServer.overworld();
                TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);
                
                NetworkNode node = graph.getNode(pos);
                if (node == null || node.getType() != NetworkNode.NodeType.ROUTER) {
                    sendEmptyResponse(exchange, 404);
                    return;
                }
                
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof com.florentdubut.telecom.block.entity.RouterBlockEntity)) {
                    sendEmptyResponse(exchange, 404);
                    return;
                }
                
                com.florentdubut.telecom.block.entity.RouterBlockEntity router = (com.florentdubut.telecom.block.entity.RouterBlockEntity) be;
                int maxDown = router.getConfiguredMaxDown();
                int maxUp = router.getConfiguredMaxUp();
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

    class CoverageTileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            
            if (minecraftServer == null || minecraftServer.overworld() == null) {
                sendEmptyResponse(exchange, 500);
                return;
            }

            ServerLevel level = minecraftServer.overworld();
            TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);

            String query = exchange.getRequestURI().getQuery();
            if (query == null) {
                sendEmptyResponse(exchange, 400);
                return;
            }

            int cx = 0;
            int cz = 0;
            String techFilter = null;
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) {
                    if (pair[0].equals("cx")) cx = Integer.parseInt(pair[1]);
                    if (pair[0].equals("cz")) cz = Integer.parseInt(pair[1]);
                    if (pair[0].equals("tech")) techFilter = pair[1]; // e.g. "5G"
                }
            }
            
            if (techFilter == null) {
                sendEmptyResponse(exchange, 400);
                return;
            }

            // Find all active antennas that broadcast this technology
            java.util.List<com.florentdubut.telecom.block.entity.AntennaBlockEntity> activeAntennas = new java.util.ArrayList<>();
            for (NetworkNode node : graph.getNodes()) {
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
            }

            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            
            // Render heatmap
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int globalX = cx * 16 + x;
                    int globalZ = cz * 16 + z;
                    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, globalX, globalZ);
                    BlockPos pos = new BlockPos(globalX, y, globalZ);
                    
                    float bestSignal = -1000f;
                    
                    for (com.florentdubut.telecom.block.entity.AntennaBlockEntity antenna : activeAntennas) {
                        BlockPos antPos = antenna.getBlockPos();
                        double distance = Math.sqrt(pos.distSqr(antPos));
                        
                        // Range optimization to avoid calculating for very far antennas
                        if (distance > 500) continue; 
                        
                        for (TelecomFrequency freq : TelecomFrequency.values()) {
                            if (antenna.isFrequencyEnabled(freq) && freq.getTechnology().equals(techFilter)) {
                                // Simplified Free-space path loss formula
                                float freeSpaceLoss = (float) (20 * Math.log10(Math.max(1, distance)) + 20 * Math.log10(freq.getFrequencyMhz()) - 27.55);
                                float power = -freeSpaceLoss;
                                
                                // Vertical penalty if antenna is below the block
                                if (antPos.getY() < y) {
                                    power -= (y - antPos.getY()) * 0.5f;
                                }
                                
                                if (power > bestSignal) {
                                    bestSignal = power;
                                }
                            }
                        }
                    }
                    
                    int color = 0x00000000; // Transparent
                    if (bestSignal > -120f) {
                        // Map signal to a color
                        if (bestSignal > -60f) {
                            color = 0x8822cc44; // Strong (Green, semi-transparent)
                        } else if (bestSignal > -90f) {
                            color = 0x88ffcc00; // Medium (Yellow, semi-transparent)
                        } else {
                            color = 0x88ff3333; // Weak (Red, semi-transparent)
                        }
                    }
                    
                    image.setRGB(x, z, color);
                }
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", baos);
            byte[] bytes = baos.toByteArray();
            
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
        
        private void sendEmptyResponse(HttpExchange exchange, int code) throws IOException {
            exchange.sendResponseHeaders(code, -1);
        }
    }
