import sys

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content = f.read()

handler = """
    class NperfMapHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws java.io.IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            
            if (minecraftServer == null || minecraftServer.overworld() == null) {
                sendEmptyResponse(exchange, 500);
                return;
            }

            net.minecraft.server.level.ServerLevel level = minecraftServer.overworld();
            com.florentdubut.telecom.network.TelecomNetworkGraph graph = com.florentdubut.telecom.network.TelecomNetworkGraph.get(level);

            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (java.util.Map.Entry<Long, Integer> entry : graph.getRecordedCoverage().entrySet()) {
                if (!first) json.append(",");
                long pos = entry.getKey();
                int x = (int)(pos >> 32);
                int z = (int)pos;
                int val = entry.getValue();
                int techId = val >> 8;
                int signal = val & 0xFF;
                
                json.append("{\\"x\\":").append(x)
                    .append(",\\"z\\":").append(z)
                    .append(",\\"t\\":").append(techId)
                    .append(",\\"s\\":").append(signal).append("}");
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
"""

# The file currently ends with:
#         }
#     }
# }

content = content.replace("    }\n}\n", "    }\n" + handler)

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content)

