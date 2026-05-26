import sys

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content = f.read()

handler = """
    private static class NperfMapHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws java.io.IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            
            // Allow this to be accessed even if Minecraft is not fully started? No.
            // But we don't have access to minecraftServer field easily if it's static?
            // Actually, we can make it an inner class of TelecomHttpServer instead of static.
        }
    }
"""

